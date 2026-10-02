package cartservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs

class CartDocsSuite extends CatsEffectSuite {

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      endpoint = CartRoutes.serverEndpoint[IO](store, NoOpLogger[IO])
      routes = Docs.routes[IO]("Cart Service", "1.0", List(endpoint))
      request = Request[IO](Method.POST, uri"/carts")
        .withEntity(CreateCartRequest(/* codegen:fields:TEST_CREATE_REQUEST_ARGS */))
      response <- routes.orNotFound.run(request)
      entity <- response.as[CartResponse]
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/carts"))
    }
  }
}
