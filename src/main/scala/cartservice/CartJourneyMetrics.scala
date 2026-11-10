// SPDX-License-Identifier: MIT

package cartservice

import cartservice.domain._
import cats.Monad
import cats.syntax.all._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter

/** Cart business telemetry (gluon PERF-4.8), as a `CartStore` decorator:
  * `cart.operations{operation, outcome}` for every write — `operation` one of
  * `create`, `add_item`, `remove_item`, `set_quantity`, `delete`; `outcome`
  * `ok` or the error (`not_found`, `invalid_quantity`, `invalid_sku`).
  */
object CartJourneyMetrics {

  def outcome(error: CartError): String = error match {
    case CartNotFound    => "not_found"
    case InvalidQuantity => "invalid_quantity"
    case InvalidSku      => "invalid_sku"
  }

  def instrument[F[_]: Monad](
      store: CartStore[F],
      meter: Meter[F]
  ): F[CartStore[F]] =
    meter.counter[Long]("cart.operations").create.map { operations =>
      def count[E <: CartError, A](operation: String)(
          fa: F[Either[E, A]]
      ): F[Either[E, A]] =
        fa.flatTap(result =>
          operations.inc(
            Attribute("operation", operation),
            Attribute("outcome", result.fold(outcome, _ => "ok"))
          )
        )

      new CartStore[F] {
        export store.{get, ping}

        def create(): F[Cart] =
          store
            .create()
            .flatTap(_ =>
              operations.inc(
                Attribute("operation", "create"),
                Attribute("outcome", "ok")
              )
            )

        def addItem(
            id: CartId,
            sku: Sku,
            quantity: Quantity
        ): F[Either[CartError, Cart]] =
          count("add_item")(store.addItem(id, sku, quantity))

        def removeItem(
            id: CartId,
            sku: Sku
        ): F[Either[CartNotFound.type, Cart]] =
          count("remove_item")(store.removeItem(id, sku))

        def setQuantity(
            id: CartId,
            sku: Sku,
            target: Option[Quantity]
        ): F[Either[CartNotFound.type, Cart]] =
          count("set_quantity")(store.setQuantity(id, sku, target))

        def delete(id: CartId): F[Either[CartNotFound.type, Unit]] =
          count("delete")(store.delete(id))
      }
    }
}
