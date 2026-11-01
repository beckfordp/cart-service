package cartservice.domain

/** Expected, nameable cart failures, carried in `Either` from detection to the
  * HTTP edge (scala-fp.md law 4).
  */
sealed trait CartError

/** No cart with that id (never created, deleted, or expired by TTL). */
case object CartNotFound extends CartError

/** A quantity outside 1..[[Quantity.Max]], or an add that would take a line
  * past it.
  */
case object InvalidQuantity extends CartError

/** A sku that is blank, too long, or uses the reserved `__` prefix. */
case object InvalidSku extends CartError
