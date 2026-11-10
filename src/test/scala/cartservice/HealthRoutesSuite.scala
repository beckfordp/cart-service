// SPDX-License-Identifier: MIT

package cartservice

import cats.effect.IO
import cartservice.domain.{Cart, CartError, CartId, CartNotFound, Quantity, Sku}
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends CatsEffectSuite {

  private val readyStore: CartStore[IO] =
    new CartStore[IO] {
      def create(): IO[Cart] = IO.raiseError(new NotImplementedError())
      def get(id: CartId): IO[Option[Cart]] = IO.pure(None)
      def addItem(
          id: CartId,
          sku: Sku,
          quantity: Quantity
      ): IO[Either[CartError, Cart]] = IO.pure(Left(CartNotFound))
      def removeItem(
          id: CartId,
          sku: Sku
      ): IO[Either[CartNotFound.type, Cart]] =
        IO.pure(Left(CartNotFound))
      def setQuantity(
          id: CartId,
          sku: Sku,
          target: Option[Quantity]
      ): IO[Either[CartNotFound.type, Cart]] = IO.pure(Left(CartNotFound))
      def delete(id: CartId): IO[Either[CartNotFound.type, Unit]] =
        IO.pure(Left(CartNotFound))
      def ping: IO[Boolean] = IO.pure(true)
    }

  private val notReadyStore: CartStore[IO] =
    new CartStore[IO] {
      def create(): IO[Cart] = IO.raiseError(new NotImplementedError())
      def get(id: CartId): IO[Option[Cart]] = IO.pure(None)
      def addItem(
          id: CartId,
          sku: Sku,
          quantity: Quantity
      ): IO[Either[CartError, Cart]] = IO.pure(Left(CartNotFound))
      def removeItem(
          id: CartId,
          sku: Sku
      ): IO[Either[CartNotFound.type, Cart]] =
        IO.pure(Left(CartNotFound))
      def setQuantity(
          id: CartId,
          sku: Sku,
          target: Option[Quantity]
      ): IO[Either[CartNotFound.type, Cart]] = IO.pure(Left(CartNotFound))
      def delete(id: CartId): IO[Either[CartNotFound.type, Unit]] =
        IO.pure(Left(CartNotFound))
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
