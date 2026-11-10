// SPDX-License-Identifier: MIT

package cartservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs
import sttp.tapir.EndpointOutput
import sttp.tapir.server.ServerEndpoint

class CartDocsSuite extends CatsEffectSuite {

  private def servedEndpoints(
      store: CartStore[IO]
  ): List[ServerEndpoint[Any, IO]] =
    Main.endpoints[IO](store, NoOpLogger[IO])

  /** Every fixed status an output can produce (tapir's own traversal is
    * internal).
    */
  private def statuses(output: EndpointOutput[?]): List[Int] =
    output match {
      case EndpointOutput.FixedStatusCode(code, _, _) => List(code.code)
      case EndpointOutput.OneOf(variants, _)          =>
        variants.flatMap(v => statuses(v.output))
      case EndpointOutput.Pair(left, right, _, _) =>
        statuses(left) ++ statuses(right)
      case EndpointOutput.MappedPair(pair, _) => statuses(pair)
      case _                                  => Nil
    }

  private def errorStatuses(endpoint: ServerEndpoint[Any, IO]): List[Int] =
    statuses(endpoint.endpoint.errorOutput).sorted

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      endpoint = CartRoutes.serverEndpoint[IO](store, NoOpLogger[IO])
      routes = Docs.routes[IO]("Cart Service", "1.0", List(endpoint))
      request = Request[IO](Method.POST, uri"/carts")
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

  test("each endpoint documents only the errors it can return") {
    for {
      store <- CartStore.inMemory[IO]
    } yield {
      val endpoints = servedEndpoints(store)
      assertEquals(
        endpoints.map(errorStatuses),
        List(
          Nil, // POST /carts
          List(404), // GET /carts/{id}
          List(400, 400, 404), // POST /carts/{id}/items
          List(404), // DELETE /carts/{id}/items/{sku}
          List(404), // DELETE /carts/{id}
          Nil, // GET /health
          List(503) // GET /health/ready
        )
      )
    }
  }

  test("every endpoint is documented") {
    for {
      store <- CartStore.inMemory[IO]
    } yield {
      val endpoints = servedEndpoints(store)
      assertEquals(
        Docs.undocumented(endpoints.map(_.endpoint)).map(_.render),
        Nil
      )
    }
  }
}
