# Sangria Hubburu plugin

A middleware for integrating Hubburu with Sangria (Scala) GraphQL

## Installation

```sbt
libraryDependencies += "com.hubburu" %% "hubburu-sangria-middleware" % "0.0.4"
```

## Usage

These are the integration points you need to make to integrate with Hubburu.

1. Add your API key
2. Upload schema SDL to Hubburu
3. Send operation reports to Hubburu
4. (Optional) Send reports of errors outside the GraphQL lifecycle
5. (Optional) Measure functions outside of the GraphQL context

### Adding Your API Key

Register for Hubburu, and you will be able to access your API Key from there. The recommended way is to add it to your environment variables. You can also add it manually to the Hubburu SDK calls.

### Upload schema

Uploading a schema is done through the `Hubburu.pushSchema` function. Typically this is done in a CI pipeline or on server startup.

### Send operation reports

This is done by adding the Hubburu middleware to the list of Sangria middlewares. Example:

```scala
import com.hubburu._

object Server extends App with CirceHttpSupport {
  ...
  val schema = SchemaDefinition.MySchema

  Hubburu.pushSchema(schema) // Or in a CI pipeline

  val hubburuMiddleware = new Hubburu.HubburuMiddleware[UserContext](
    getRequestId = ctx => ctx.ctx.requestId,
    sampleFunction = (_) => Math.random() > 0.99 // True will trace GraphQL fields.
  )

  ...
  ...

  val route: Route =
    optionalHeaderValueByName("X-Apollo-Tracing") { tracing =>
      path("graphql") {
        graphQLPlayground ~
          prepareGraphQLRequest {
            case Success(req) =>
              val graphQLResponse = Executor
                .execute(
                  schema = schema,
                  ...
                  variables = req.variables,
                  operationName = req.operationName,
                  middleware = hubburuMiddleware :: Nil
                )
```

You control if a trace should be gathered (which adds overhead!) with the `sampleFunction` parameter.

Hubburu will send the operation asynchronously in the `afterQuery` middleware hook using `sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend`.

### Errors outside the GraphQL lifecycle

Some errors will ignore the middleware, for example query validation errors. These requests will not show up at all in Hubburu unless you add another integration point. We suggest doing that, because otherwise you might not notice errors that are caused by removal of a field in use by clients.

```scala
...
case Success(req) =>
  ...
  .map(OK -> _)
  .recover {
    case error: QueryAnalysisError => {
      Hubburu.sendOperation(
        new Hubburu.HubburuReport(
          sdl = req.query,
          error = error,
          operationName = req.operationName.getOrElse("?"),

          // You will need to map these to your own implementation
          clientName = "value_or_skipped",
          clientVersion = "value_or_skipped",
          requestId = "skip_to_get_generated_id"
        )
      )
    }

```

### Measure Functions Outside of the GraphQL Context

Hubburu supports measuring functions outside the GraphQL Context. We call these "Loader functions".

To use this function you will need to provide your own Request ID. Hubburu will use this Request ID to connect the loader call to the GraphQL trace.
A common pattern is to generate a UUID v4 and attach it to your GraphQL context, and track that id throughout your different services. That kind of ID is suitable for this use case.

```scala
val measureEnd = Hubburu.measureLoader("NameVisibleInHubburuDashboard", requestId) // Add the same request id as the middleware getRequestId would get

... // Do some sync or async work

measureEnd() // The first call to this function will add the trace to the report, future calls will be ignored

```

## Development & Testing

This plugin is being developed and tested in another repository. You are welcome to send bug reports either as an issue on Github or to [hello@hubburu.com](mailto:hello@hubburu.com).
