package cartservice.domain

import java.time.Instant

/** A cart: sku -> quantity lines plus timestamps. Lines are only ever written
  * through the pure operations below, from parsed [[Sku]]/[[Quantity]] values,
  * so every quantity is in 1..[[Quantity.Max]].
  */
final case class Cart(
    id: CartId,
    items: Map[String, Int],
    createdAt: Instant,
    updatedAt: Instant
)

object Cart {

  def empty(id: CartId, now: Instant): Cart = Cart(id, Map.empty, now, now)

  /** Adds `quantity` to the sku's line (inserting it if absent). A result above
    * [[Quantity.Max]] is `InvalidQuantity` and leaves the cart as is.
    */
  def addItem(
      cart: Cart,
      sku: Sku,
      quantity: Quantity,
      now: Instant
  ): Either[InvalidQuantity.type, Cart] = {
    val key = Sku.value(sku)
    val total = cart.items.getOrElse(key, 0).toLong + Quantity.value(quantity)
    Either.cond(
      total <= Quantity.Max,
      cart.copy(items = cart.items + (key -> total.toInt), updatedAt = now),
      InvalidQuantity
    )
  }

  /** Drops the sku's line entirely; a sku not in the cart is a no-op. */
  def removeItem(cart: Cart, sku: Sku, now: Instant): Cart =
    cart.copy(items = cart.items - Sku.value(sku), updatedAt = now)

  /** Sets the sku's line exactly, or removes it on `None` (see
    * [[Quantity.parseTarget]]).
    */
  def setQuantity(
      cart: Cart,
      sku: Sku,
      target: Option[Quantity],
      now: Instant
  ): Cart =
    target match {
      case Some(quantity) =>
        cart.copy(
          items = cart.items + (Sku.value(sku) -> Quantity.value(quantity)),
          updatedAt = now
        )
      case None => removeItem(cart, sku, now)
    }
}
