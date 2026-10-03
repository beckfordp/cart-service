package cartservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{
  ERROR,
  INFO,
  WARN
}
import purerest.tracing.{ServerTracing, Tracing}
import sttp.tapir._
import sttp.tapir.server.http4s.Http4sServerInterpreter

class CartRoutesSuite extends CatsEffectSuite {

  test("CartResponse carries a cart's items through from the entity") {
    val entity = Cart(
      id = "cart-1",
      items = Map("sku-1" -> 2, "sku-2" -> 5),
      createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
      updatedAt = java.time.Instant.parse("2026-01-01T00:00:00Z")
    )
    assertEquals(
      CartResponse(entity).items,
      Map("sku-1" -> 2, "sku-2" -> 5)
    )
  }

  test("InvalidQuantity's error output maps to 400 with a JSON error body") {
    val testEndpoint: PublicEndpoint[Unit, CartError, Unit, Any] =
      endpoint.get
        .in("test-invalid-quantity")
        .errorOut(CartRoutes.invalidQuantityOutput)
    val routes = Http4sServerInterpreter[IO]().toRoutes(
      testEndpoint.serverLogic[IO](_ => IO.pure(Left(InvalidQuantity)))
    )
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/test-invalid-quantity")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.BadRequest)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  private def failingStore(error: Throwable): CartStore[IO] =
    new CartStore[IO] {
      def create(): IO[Cart] = IO.raiseError(error)
      def get(id: String): IO[Option[Cart]] = IO.pure(None)
      def addItem(id: String, sku: String, quantity: Int): IO[Option[Cart]] =
        IO.raiseError(error)
      def removeItem(id: String, sku: String): IO[Option[Cart]] =
        IO.raiseError(error)
      def setQuantity(
          id: String,
          sku: String,
          quantity: Int
      ): IO[Option[Cart]] = IO.raiseError(error)
      def delete(id: String): IO[Boolean] = IO.raiseError(error)
      def ping: IO[Boolean] = IO.raiseError(error)
    }

  test("POST /carts returns 201 with the created entity") {
    for {
      store <- CartStore.inMemory[IO]
      routes = CartRoutes.routes[IO](store, NoOpLogger[IO])
      request = Request[IO](Method.POST, uri"/carts")
      response <- routes.orNotFound.run(request)
      entity <- response.as[CartResponse]
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
    }
  }

  test("GET /carts/{id} returns 200 with the persisted entity") {
    for {
      store <- CartStore.inMemory[IO]
      routes = CartRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/carts")
      )
      created <- postResponse.as[CartResponse]
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/carts" / created.id)
      )
      fetched <- getResponse.as[CartResponse]
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      assertEquals(fetched, created)
    }
  }

  test(
    "GET /carts/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      routes = CartRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/carts" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /carts logs a received-request line and a completed line with structured context"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CartRoutes.routes[IO](store, testLogger)
      request = Request[IO](Method.POST, uri"/carts")
      response <- routes.orNotFound.run(request)
      entity <- response.as[CartResponse]
      logged <- testLogger.logged
    } yield {
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("POST")
        ),
        s"expected a received-request INFO line with method context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("cart_id").contains(entity.id)
        ),
        s"expected a completed INFO line with cart_id context, got: $infos"
      )
    }
  }

  test(
    "POST /carts logs an ERROR with the raised throwable when persisting the entity fails"
  ) {
    val boom = new RuntimeException("boom")
    for {
      testLogger <- IO.pure(StructuredTestingLogger.impl[IO]())
      routes = CartRoutes.routes[IO](failingStore(boom), testLogger)
      request = Request[IO](Method.POST, uri"/carts")
      response <- routes.orNotFound.run(request)
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.InternalServerError)
      val errors = logged.collect { case m: ERROR => m }
      assert(
        errors.exists(m => m.throwOpt.contains(boom)),
        s"expected an ERROR line with the raised throwable, got: $errors"
      )
    }
  }

  test(
    "GET /carts/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CartRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/carts")
      )
      created <- postResponse.as[CartResponse]
      _ <- testLogger.logged // drain POST's own log lines before the GET
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/carts" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("GET") &&
            m.ctx.get("cart_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("cart_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test(
    "GET /carts/{id} logs a WARN for an unknown id"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CartRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/carts" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("cart_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test(
    "DELETE /carts/{id} returns 204, and a subsequent GET returns 404"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      routes = CartRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/carts")
      )
      created <- postResponse.as[CartResponse]
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/carts" / created.id)
      )
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/carts" / created.id)
      )
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }

  test(
    "DELETE /carts/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      routes = CartRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/carts" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "DELETE /carts/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CartRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/carts")
      )
      created <- postResponse.as[CartResponse]
      _ <- testLogger.logged // drain POST's own log lines before the DELETE
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/carts" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("DELETE") &&
            m.ctx.get("cart_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("cart_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test("DELETE /carts/{id} logs a WARN for an unknown id") {
    for {
      store <- CartStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CartRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/carts" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("cart_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test(
    "wrapped routes (with tracing middleware) record a span for a handled request"
  ) {
    Tracing.test[IO]("cart-service-test").use { testTracer =>
      for {
        store <- CartStore.inMemory[IO]
        routes = ServerTracing.middleware(testTracer.tracer)(
          CartRoutes.routes[IO](store, NoOpLogger[IO])
        )
        request = Request[IO](Method.POST, uri"/carts")
        response <- routes.orNotFound.run(request)
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(spans.map(_.getName), List("POST /carts"))
      }
    }
  }
}
