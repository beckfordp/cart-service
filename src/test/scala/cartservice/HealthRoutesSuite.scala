package cartservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends CatsEffectSuite {

  private val readyStore: CartStore[IO] =
    new CartStore[IO] {
      def create(): IO[Cart] = IO.raiseError(new NotImplementedError())
      def get(id: String): IO[Option[Cart]] = IO.pure(None)
      def addItem(id: String, sku: String, quantity: Int): IO[Option[Cart]] =
        IO.pure(None)
      def removeItem(id: String, sku: String): IO[Option[Cart]] = IO.pure(None)
      def setQuantity(
          id: String,
          sku: String,
          quantity: Int
      ): IO[Option[Cart]] = IO.pure(None)
      def delete(id: String): IO[Boolean] = IO.pure(false)
      def ping: IO[Boolean] = IO.pure(true)
    }

  private val notReadyStore: CartStore[IO] =
    new CartStore[IO] {
      def create(): IO[Cart] = IO.raiseError(new NotImplementedError())
      def get(id: String): IO[Option[Cart]] = IO.pure(None)
      def addItem(id: String, sku: String, quantity: Int): IO[Option[Cart]] =
        IO.pure(None)
      def removeItem(id: String, sku: String): IO[Option[Cart]] = IO.pure(None)
      def setQuantity(
          id: String,
          sku: String,
          quantity: Int
      ): IO[Option[Cart]] = IO.pure(None)
      def delete(id: String): IO[Boolean] = IO.pure(false)
      def ping: IO[Boolean] = IO.pure(false)
    }

  test("GET /health returns 200") {
    val routes = HealthRoutes.routes[IO](readyStore)
    for {
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/health"))
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 200 when the store is ready") {
    val routes = HealthRoutes.routes[IO](readyStore)
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 503 when the store is not ready") {
    val routes = HealthRoutes.routes[IO](notReadyStore)
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.ServiceUnavailable)
  }
}
