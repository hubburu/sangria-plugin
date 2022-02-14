# Sangria Hubburu plugin

A middleware for integrating Hubburu with Sangria (Scala) GraphQL

## Installation

```sbt
libraryDependencies += "com.hubburu" %% "hubburu-sangria-middleware" % "0.0.2"
```

## Usage

There are two integration points you need to make to integrate with Hubburu.

1. Add you API key
2. Upload schema SDL to Hubburu
3. Send operation reports to Hubburu

### Adding Your API Key

Register for Hubburu, and you will be able to access your API Key from there. The recommended way is to add it to your environment variables.

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
    sampleFunction = (_) => Math.random() > 0.99
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

## Development & Testing

This plugin is being developed and tested in another repository. You are welcome to send bug reports either as an issue on Github or to [hello@hubburu.com](mailto:hello@hubburu.com).
