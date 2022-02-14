package com.hubburu

import sangria.execution._
import sangria.schema.Context
import sangria.schema.LeafType
import sangria.schema.OptionType
import sangria.schema.ScalarType
import sangria.schema.Schema
import sttp.client3._
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import java.util.UUID.randomUUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import sangria.renderer.QueryRenderer
import sangria.ast.Document

object Hubburu {

  object Gzip {
    def compressToByteArray(input: String): Array[Byte] = {
      val bytes = input.getBytes
      val bos = new ByteArrayOutputStream(bytes.length)
      val gzip = new GZIPOutputStream(bos)
      gzip.write(bytes)
      gzip.close()
      val compressed = bos.toByteArray
      bos.close()
      compressed
    }

    def compress(input: String): String = {
      val compressed = compressToByteArray(input)
      Base64.getEncoder.encodeToString(compressed)
    }
  }

  private val CONTAINS_NUMBER = "\\.[0-9]+\\."
  private val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  private val sttpBackend = AsyncHttpClientFutureBackend()
  private val MAX_TRACING_SIZE = 2000
  private val MAX_ERRORS_SIZE = 1000
  private val BASE_URL = scala.util.Properties
    .envOrElse("HUBBURU_REPORT_URL", "https://report.hubburu.com")

  val NO_TRACING = 0
  val POST_PROCESS_LISTS = 1
  val LIST_NORMALIZED = 2
  val NO_LEAF_NODES = 3

  /** Push the schema to Hubburu. Typically done in a CI pipeline or on server
    * startup.
    *
    * @param schema
    *   The new version or your schema.
    * @param environment
    *   A string to identify the current environment. Like "staging" or
    *   "production". Should match the call to new HubburuMiddleware
    * @param apiKey
    *   Defaults to the environment variable "HUBBURU_API_KEY", but if can
    *   overwrite it with this parameter.
    */
  def pushSchema[A, B](
      schema: Schema[A, B],
      environment: String = "default",
      apiKey: String = scala.util.Properties.envOrElse("HUBBURU_API_KEY", null)
  ): Unit = {
    if (apiKey == null) {
      Console.err.println("MISSING API KEY FOR HUBBURU")
      return
    }
    val sdl = Gzip.compress(schema.renderPretty)
    val sb = new StringBuilder
    sb.append("{\"environment\":\"")
      .append(environment)
      .append("\",\"sdl\":\"")
      .append(sdl)
      .append("\"}")

    try {
      val result = basicRequest
        .post(uri"$BASE_URL/schema")
        .body(sb.toString)
        .header("Content-Type", "application/json")
        .header("x-api-key", apiKey)
        .header("hubburu-plugin", "sangria-0.0.2")
        .header("Charset", "UTF-8")
        .send(backend = sttpBackend)
    } catch {
      case e: Throwable => Console.err.println(e)
    }
  }

  private def denormalizeList(
      tracing: ArrayBuffer[(String, Double, Double)],
      lookup: TrieMap[
        String,
        ((String, Double, Double), (String, Double, Double))
      ]
  ) = {
    var newTracing = ArrayBuffer[(String, Double, Double)]()
    tracing.foreach(f => {
      if (f._2 < 0) {
        val minMax = lookup.getOrElse(f._1, null)
        newTracing += minMax._1
        if (minMax._1._1 != minMax._2._1) {
          newTracing += minMax._2
        }
      } else {
        newTracing += f
      }
    })
    newTracing
  }

  private def postProcessLists(
      tracing: ArrayBuffer[(String, Double, Double)],
      lookup: TrieMap[
        String,
        ((String, Double, Double), (String, Double, Double))
      ]
  ) = {
    tracing.foreach(trace => {
      if (trace._1.matches(CONTAINS_NUMBER)) {
        val normalized = trace._1.replaceAll(CONTAINS_NUMBER, ".X.");
        val earlierValues =
          lookup.getOrElse(normalized, null)
        if (earlierValues == null) {
          lookup.put(normalized, (trace, trace))
        } else {
          var min = earlierValues._1
          var max = earlierValues._2
          var write = false
          if (trace._2 < min._2) {
            min = trace
            write = true
          }
          if (trace._2 > max._2) {
            max = trace
            write = true
          }
          if (write) {
            lookup.put(normalized, (min, max))
          }
        }
      }
    })
    val newList = ArrayBuffer[(String, Double, Double)]()
    tracing.foreach(trace => {
      if (trace._1.matches(CONTAINS_NUMBER)) {

        val normalized = trace._1.replaceAll(CONTAINS_NUMBER, ".X.");
        val earlierValues =
          lookup.getOrElse(normalized, null)
        if (earlierValues != null) {
          newList += earlierValues._1
          if (earlierValues._1._1 != earlierValues._2._1) {
            newList += earlierValues._2
          }
          lookup.remove(normalized)
        }
      } else {
        newList += trace
      }
    })
    newList
  }

  def sendOperation(
      report: HubburuReport,
      apiKey: String = scala.util.Properties.envOrElse("HUBBURU_API_KEY", null),
      tracingMode: Int = NO_TRACING
  ): Unit = {
    try {
      if (apiKey == null) {
        Console.err.println("MISSING API KEY FOR HUBBURU")
      }
      val operationName = report.operationName
      val postProcessingStart = System.nanoTime()
      val totalMs = postProcessingStart - report.startTime

      val sb = new StringBuilder
      sb.append("{\"environment\":\"")
        .append(report.environment)
        .append("\",\"gzippedOperationBody\":\"")
        .append(Gzip.compress(QueryRenderer.renderPretty(report.sdl)))
        .append("\",\"totalMs\":")
        .append(toReportMs(totalMs))
        .append(",\"resolvers\":\"")

      var tracing = report.tracing

      if (tracingMode == LIST_NORMALIZED) {
        tracing = denormalizeList(tracing, report.listLookup)
      }

      var zipped = Gzip.compressToByteArray(
        getTracingString(tracing).toString()
      )
      var resolversTooLarge = false
      if (zipped.length > MAX_TRACING_SIZE) {
        if (tracingMode == POST_PROCESS_LISTS) {
          tracing = postProcessLists(tracing, report.listLookup)
        }

        zipped = Gzip.compressToByteArray(
          getTracingString(tracing.slice(0, 200))
        )
        resolversTooLarge = true
      }
      var errorsTooLarge = false

      sb.append(Base64.getEncoder.encodeToString(zipped))
        .append("\",\"createdAt\":\"")
        .append(OffsetDateTime.now().format(dateFormatter))
        .append("\",\"operationName\":\"")
        .append(operationName)
        .append("\",")
      if (report.loaders.size > 0) {
        sb.append("\"loaders\":{")

        report.loaders.foreach((v) => {
          sb.append("\"").append(v._1).append("\":[")

          v._2.foreach(t => {
            sb.append("[").append(t._1).append(",").append(t._2).append("],")
          })

          sb.deleteCharAt(sb.length() - 1)
          sb.append("]},")
        })
      }
      if (report.clientName != null) {
        sb.append("\"clientName\":\"")
          .append(report.clientName)
          .append("\",")
      }
      if (report.clientVersion != null) {
        sb.append("\"clientVersion\":\"")
          .append(report.clientVersion)
          .append("\",")
      }
      sb.append(("\"errors\":"))
      if (report.errors.length > 0) {
        var compressedErrs =
          Gzip.compressToByteArray(getErrorString(report.errors))

        if (compressedErrs.length > MAX_ERRORS_SIZE) {
          errorsTooLarge = true
          compressedErrs = Gzip.compressToByteArray(
            getErrorString((report.errors.slice(0, 5)))
          )
        }
        sb.append("\"")
          .append(Base64.getEncoder.encodeToString(compressedErrs))
          .append("\"")
      } else {
        sb.append("null")
      }

      sb.append(",\"meta\": {\"postProcessingTime\":")
        .append(toReportMs((System.nanoTime() - postProcessingStart)))

      if (resolversTooLarge) {
        sb.append(",\"resolversTooLarge\":").append(report.tracing.length)
      }
      if (errorsTooLarge) {
        sb.append(",\"errorsTooLarge\":").append(report.errors.length)
      }
      sb.append("}}")
      val result = basicRequest
        .post(uri"$BASE_URL/operation")
        .body(sb.toString())
        .header("Content-Type", "application/json")
        .header("x-api-key", apiKey)
        .header("hubburu-plugin", "sangria-0.0.2")
        .header("Charset", "UTF-8")
        .send(backend = sttpBackend)
    } catch {
      case e: Throwable => Console.err.println(e)
    }
  }

  class HubburuReport(
      val sdl: Document,
      val environment: String = "default",
      val operationName: String,
      val requestId: String = randomUUID().toString(),
      val startTime: Long = System.nanoTime(),
      val isSampled: Boolean = true,
      val clientName: String = null,
      val clientVersion: String = null,
      error: Throwable = null
  ) {
    // Key, duration, offset
    val tracing = ArrayBuffer[(String, Double, Double)]()
    val loaders =
      new TrieMap[String, Vector[(Double, Double)]]()
    val errors = ArrayBuffer[Throwable]()
    val listLookup =
      new TrieMap[
        String,
        ((String, Double, Double), (String, Double, Double))
      ]()

    if (error != null) {
      errors += error
    }
  }

  def getTracingString(list: ArrayBuffer[(String, Double, Double)]) = {
    val resolversSb = new StringBuilder("[")
    list.foreach(f => {
      val key = f._1
      val time = f._2
      val offset = f._3
      resolversSb
        .append("[\"")
        .append(key)
        .append("\",")
        .append(time)
        .append(",")
        .append(offset)
        .append("],")
    })
    resolversSb.deleteCharAt(resolversSb.length() - 1).append(("]"))
    if (resolversSb.length() == 1) {
      ""
    } else {
      resolversSb.toString()
    }
  }

  def getErrorString(list: ArrayBuffer[Throwable]) = {
    val errorSb = new StringBuilder("[")
    list.foreach(e => {
      println(e.getMessage())
      errorSb
        .append("{\"message\":\"")
        .append(e.getMessage().replaceAll("\n", ""))
        .append("\",\"details\":\"")
        .append(
          e.getStackTrace()
            .slice(0, 5)
            .map(e => {
              val file = e.getFileName()
              val line = e.getLineNumber()
              val method = e.getMethodName()
              file + ":" + line + " (" + method + ")"
            })
            .mkString(",")
        )
        .append("\"},")
    })
    errorSb.deleteCharAt(errorSb.length() - 1).append("]")
    errorSb.toString
  }
  def toReportMs(nanos: Long) = {
    val withLessPrecision = nanos / 10000
    withLessPrecision / 100.0
  }

  val reportMap = new TrieMap[String, HubburuReport]()

  def measureLoader(name: String, requestId: String): () => Unit = {
    val before = System.nanoTime()

    if (!reportMap.contains(requestId)) {
      Console.err.println("Invalid request id " + requestId)
      return () => {}
    }
    var hasMeasured = new AtomicBoolean(false)

    () => {
      if (hasMeasured.compareAndSet(false, true)) {
        val after = System.nanoTime()
        val report = reportMap.get(requestId).get
        val tuple = (
          toReportMs(after - before),
          toReportMs(
            before - report.startTime
          )
        )
        var loaders =
          report.loaders.getOrElse(name, Vector.empty).appended(tuple)
        report.loaders.put(name, loaders)

      }
    }
  }

  /** @param environment
    *   A string to identify the current environment. Like "staging" or
    *   "production". Should match the call to Hubburu.pushSchema.
    *
    * @param tracingMode
    *   How the middleware should gather traces. Different modes have different
    *   performance implications. Valid values are: Hubburu.NO_TRACING (Disable
    *   tracing - fastest), Hubburu.POST_PROCESS_LISTS (Enable all tracing - in
    *   case of large payloads, post process list results to only include lowest
    *   and highest trace), Hubburu.LIST_NORMALIZED (Always gather highest and
    *   lowest trace, to avoid post processing), Hubburu.NO_LEAF_NODES (Avoid
    *   leaf nodes to reduce payload size and processing).
    *
    * @param sampleFunction
    *   A function to determine if a certain operation should be traced. It is
    *   common practice in performance tracing to only measure a subset of
    *   operations to reduce overhead. A simple function you could use is
    *   something like `(_) => Math.random() > 0.99`. Return true to include.
    *
    * @param shouldSend
    *   A function to let you avoid sending a report that only creates noise in
    *   your Hubburu dashboard (or pushes you to a new pricing tier).
    *
    * @param getClientName
    *   A function to provide the current client name, to better track usage in
    *   Hubburu.
    *
    * @param getClientVersion
    *   A function to provide the current client version, to better track usage
    *   in Hubburu.
    *
    * @param getRequestId
    *   Some companies have a global request id to track a request chain through
    *   their systems, you may provide such an ID here. You can use the ID you
    *   provide here to look up a run in your Hubburu dashboard. You will need
    *   to provide the same id to the Hubburu.measureLoader function if you
    *   choose to use that. A common pattern is to generate a random UUID and
    *   add that to your GraphQL context.
    *
    * @param apiKey
    *   Defaults to the environment variable "HUBBURU_API_KEY", but if can
    *   overwrite it with this parameter.
    */
  class HubburuMiddleware[Ctx](
      environment: String = "default",
      tracingMode: Int = POST_PROCESS_LISTS,
      sampleFunction: ((MiddlewareQueryContext[Ctx, _, _]) => Boolean) =
        (_: Any) => true,
      shouldSend: ((HubburuReport) => Boolean) = (_) => true,
      getClientName: ((MiddlewareQueryContext[Ctx, _, _]) => String) =
        (_: Any) => null,
      getClientVersion: ((MiddlewareQueryContext[Ctx, _, _]) => String) =
        (_: Any) => null,
      getRequestId: ((MiddlewareQueryContext[Ctx, _, _]) => String) =
        (_: Any) => randomUUID().toString(),
      apiKey: String = scala.util.Properties.envOrElse("HUBBURU_API_KEY", null)
  ) extends Middleware[Ctx]
      with MiddlewareAfterField[Ctx]
      with MiddlewareErrorField[Ctx] {

    type FieldVal = Long
    type QueryVal = HubburuReport

    def beforeQuery(context: MiddlewareQueryContext[Ctx, _, _]) = {
      val requestId = getRequestId(context)
      val report =
        new HubburuReport(
          environment = environment,
          clientVersion = getClientVersion(context),
          clientName = getClientName(context),
          sdl = context.queryAst,
          isSampled = sampleFunction(context),
          requestId = requestId,
          operationName = context.operationName.getOrElse("?")
        )
      reportMap.put(
        requestId,
        report
      )
      report
    }

    def afterQuery(
        queryVal: QueryVal,
        context: MiddlewareQueryContext[Ctx, _, _]
    ): Unit = {
      reportMap.remove(queryVal.requestId)
      if (!shouldSend(queryVal)) {
        return
      }
      Hubburu.sendOperation(queryVal, apiKey, tracingMode)
    }

    def beforeField(
        queryVal: QueryVal,
        mctx: MiddlewareQueryContext[Ctx, _, _],
        ctx: Context[Ctx, _]
    ) =
      continue(System.nanoTime())

    def afterField(
        queryVal: QueryVal,
        fieldVal: FieldVal,
        value: Any,
        mctx: MiddlewareQueryContext[Ctx, _, _],
        ctx: Context[Ctx, _]
    ): Option[Any] = {
      if (tracingMode == NO_TRACING || !queryVal.isSampled) return None

      if (tracingMode == NO_LEAF_NODES) {
        val fieldType = ctx.field.fieldType
        fieldType match {
          case OptionType(ofType) => {
            if (ofType.isInstanceOf[LeafType]) return None
          }
          case leaf: LeafType => return None
          case _              => {}
        }
      }

      val key = ctx.path.path.mkString(".")
      val now = System.nanoTime()

      val duration = toReportMs(now - fieldVal)
      val tuple = (
        key,
        duration,
        toReportMs(fieldVal - queryVal.startTime)
      )

      if (tracingMode == LIST_NORMALIZED) {
        val sb = new StringBuilder
        var isList = false
        ctx.path.path
          .foreach(f => {
            if (f.isInstanceOf[Integer]) {
              sb.append("X")
              isList = true
            } else {
              sb.append(f.toString())
            }
            sb.append(".")
          })
        if (!isList) {
          queryVal.tracing += (
            tuple
          )
          return None
        }
        sb.deleteCharAt(sb.length() - 1)
        val normalizedKey = sb.toString()

        val earlierValues = queryVal.listLookup.getOrElse(normalizedKey, null)
        if (earlierValues == null) {
          queryVal.listLookup.put(normalizedKey, (tuple, tuple))
          val placeholder: Double = -1
          queryVal.tracing += ((normalizedKey, placeholder, placeholder))
        } else {
          var min = earlierValues._1
          var max = earlierValues._2
          var write = false
          if (duration < min._2) {
            min = tuple
            write = true
          }
          if (duration > max._2) {
            max = tuple
            write = true
          }
          if (write) {
            queryVal.listLookup.put(normalizedKey, (min, max))
          }
        }
      } else {
        queryVal.tracing += (
          tuple
        )
      }
      None
    }

    def fieldError(
        queryVal: QueryVal,
        fieldVal: FieldVal,
        error: Throwable,
        mctx: MiddlewareQueryContext[Ctx, _, _],
        ctx: Context[Ctx, _]
    ): Unit = {
      queryVal.errors += (error)
      if (tracingMode == NO_TRACING || !queryVal.isSampled) return

      val key = ctx.path.path.mkString(".")
      val now = System.nanoTime()

      queryVal.tracing += (
        (
          key,
          toReportMs(now - fieldVal),
          toReportMs(fieldVal - queryVal.startTime)
        )
      )
    }
  }
}
