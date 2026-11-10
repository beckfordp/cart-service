// SPDX-License-Identifier: MIT

package cartservice

import cartservice.domain.{
  Cart,
  CartId,
  InvalidQuantity,
  InvalidSku,
  Quantity,
  Sku
}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Instant
import java.util.UUID

class CartDomainSuite extends ScalaCheckSuite {

  private val t0 = Instant.parse("2026-11-01T10:00:00Z")
  private val t1 = Instant.parse("2026-11-01T10:05:00Z")
  private val id =
    CartId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  private def sku(raw: String): Sku =
    Sku.parse(raw).fold(e => fail(s"bad sku fixture: $e"), identity)

  private def qty(n: Int): Quantity =
    Quantity.parse(n).fold(e => fail(s"bad quantity fixture: $e"), identity)

  test("Sku.parse accepts an ordinary catalog sku") {
    assertEquals(Sku.parse("SKU-MUG-001").map(Sku.value), Right("SKU-MUG-001"))
  }

  test("Sku.parse rejects blank, reserved-prefix and over-long skus") {
    assertEquals(Sku.parse(""), Left(InvalidSku))
    assertEquals(Sku.parse("   "), Left(InvalidSku))
    assertEquals(Sku.parse("__created_at"), Left(InvalidSku))
    assertEquals(Sku.parse("__updated_at"), Left(InvalidSku))
    assertEquals(Sku.parse("__anything"), Left(InvalidSku))
    assertEquals(Sku.parse("x" * (Sku.MaxLength + 1)), Left(InvalidSku))
    assert(Sku.parse("x" * Sku.MaxLength).isRight)
  }

  test("Quantity.parse accepts 1..Max and rejects everything else") {
    assertEquals(Quantity.parse(1).map(Quantity.value), Right(1))
    assertEquals(
      Quantity.parse(Quantity.Max).map(Quantity.value),
      Right(Quantity.Max)
    )
    assertEquals(Quantity.parse(0), Left(InvalidQuantity))
    assertEquals(Quantity.parse(-1), Left(InvalidQuantity))
    assertEquals(Quantity.parse(Quantity.Max + 1), Left(InvalidQuantity))
  }

  test("Quantity.parseTarget maps <= 0 to removal and rejects > Max") {
    assertEquals(Quantity.parseTarget(0), Right(None))
    assertEquals(Quantity.parseTarget(-5), Right(None))
    assertEquals(
      Quantity.parseTarget(7).map(_.map(Quantity.value)),
      Right(Some(7))
    )
    assertEquals(Quantity.parseTarget(Quantity.Max + 1), Left(InvalidQuantity))
  }

  test("Cart.empty has no items and equal timestamps") {
    assertEquals(Cart.empty(id, t0), Cart(id, Map.empty, t0, t0))
  }

  test("Cart.addItem inserts a new line and bumps updatedAt") {
    assertEquals(
      Cart.addItem(Cart.empty(id, t0), sku("sku-1"), qty(3), t1),
      Right(Cart(id, Map("sku-1" -> 3), t0, t1))
    )
  }

  test("Cart.addItem increments an existing line") {
    val result = for {
      once <- Cart.addItem(Cart.empty(id, t0), sku("sku-1"), qty(3), t0)
      twice <- Cart.addItem(once, sku("sku-1"), qty(2), t1)
    } yield twice.items
    assertEquals(result, Right(Map("sku-1" -> 5)))
  }

  test("Cart.addItem past Max is InvalidQuantity") {
    val result = for {
      full <- Cart.addItem(
        Cart.empty(id, t0),
        sku("sku-1"),
        qty(Quantity.Max),
        t0
      )
      over <- Cart.addItem(full, sku("sku-1"), qty(1), t1)
    } yield over
    assertEquals(result, Left(InvalidQuantity))
  }

  test("Cart.removeItem drops the line; absent sku is a no-op") {
    val cart = Cart(id, Map("sku-1" -> 2, "sku-2" -> 1), t0, t0)
    assertEquals(
      Cart.removeItem(cart, sku("sku-1"), t1),
      Cart(id, Map("sku-2" -> 1), t0, t1)
    )
    assertEquals(Cart.removeItem(cart, sku("sku-9"), t1).items, cart.items)
  }

  test("Cart.setQuantity sets exactly, or removes the line on None") {
    val cart = Cart(id, Map("sku-1" -> 2), t0, t0)
    assertEquals(
      Cart.setQuantity(cart, sku("sku-1"), Some(qty(10)), t1).items,
      Map("sku-1" -> 10)
    )
    assertEquals(
      Cart.setQuantity(cart, sku("sku-1"), None, t1).items,
      Map.empty[String, Int]
    )
  }

  private val quantities: Gen[Quantity] =
    Gen.choose(1, Quantity.Max).map(qty)

  property("a line built by any sequence of adds stays within 1..Max") {
    forAll(Gen.listOf(quantities)) { adds =>
      val cart = adds.foldLeft(Cart.empty(id, t0)) { (acc, q) =>
        Cart.addItem(acc, sku("sku-1"), q, t1).getOrElse(acc)
      }
      cart.items.values.forall(n => n >= 1 && n <= Quantity.Max)
    }
  }

  property("addItem either sums exactly or rejects, never overflows") {
    forAll(quantities, quantities) { (a, b) =>
      val start = Cart(id, Map("sku-1" -> Quantity.value(a)), t0, t0)
      val sum = Quantity.value(a) + Quantity.value(b)
      Cart.addItem(start, sku("sku-1"), b, t1) match {
        case Right(next) =>
          sum <= Quantity.Max && next.items.get("sku-1").contains(sum)
        case Left(err) => sum > Quantity.Max && err == InvalidQuantity
      }
    }
  }

  property("parseTarget never yields a quantity outside 1..Max") {
    forAll(Gen.choose(Int.MinValue, Int.MaxValue)) { n =>
      Quantity.parseTarget(n) match {
        case Right(Some(q)) =>
          Quantity.value(q) >= 1 && Quantity.value(q) <= Quantity.Max
        case Right(None) => n <= 0
        case Left(_)     => n > Quantity.Max
      }
    }
  }
}
