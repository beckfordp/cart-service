// SPDX-License-Identifier: MIT

package cartservice.domain

/** A cart line's quantity: 1..[[Quantity.Max]]. The cap keeps a line inside
  * `Int` (and exact in Redis Lua's doubles) however many adds land on it.
  */
opaque type Quantity = Int

object Quantity {

  val Max: Int = 999

  def parse(raw: Int): Either[InvalidQuantity.type, Quantity] =
    if (raw >= 1 && raw <= Max) Right(raw) else Left(InvalidQuantity)

  /** A set-quantity target: `<= 0` means remove the line (`None`). */
  def parseTarget(raw: Int): Either[InvalidQuantity.type, Option[Quantity]] =
    if (raw <= 0) Right(None) else parse(raw).map(Some(_))

  def value(quantity: Quantity): Int = quantity
}
