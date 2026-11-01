package cartservice.domain

/** A catalog sku as stored in a cart line. Parsed once at the HTTP boundary:
  * non-blank, at most [[Sku.MaxLength]] chars, and never starting with `__` -
  * that prefix is reserved for the cart hash's own metadata fields
  * (`__created_at`/`__updated_at`), so a sku can never overwrite or delete
  * them.
  */
opaque type Sku = String

object Sku {

  val MaxLength: Int = 128

  val ReservedPrefix: String = "__"

  def parse(raw: String): Either[InvalidSku.type, Sku] =
    if (
      raw.trim.isEmpty || raw.length > MaxLength ||
      raw.startsWith(ReservedPrefix)
    ) Left(InvalidSku)
    else Right(raw)

  def value(sku: Sku): String = sku
}
