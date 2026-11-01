package cartservice

import cartservice.domain.{Quantity, Sku}

/** Parsed-value literals for tests: fails loudly on a bad fixture. */
object CartFixtures {

  def sku(raw: String): Sku =
    Sku.parse(raw).fold(e => throw new AssertionError(s"bad sku: $e"), identity)

  def qty(n: Int): Quantity =
    Quantity
      .parse(n)
      .fold(e => throw new AssertionError(s"bad quantity: $e"), identity)

  def target(n: Int): Option[Quantity] =
    Quantity
      .parseTarget(n)
      .fold(e => throw new AssertionError(s"bad target: $e"), identity)
}
