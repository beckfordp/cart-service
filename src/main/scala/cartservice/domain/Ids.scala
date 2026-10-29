package cartservice.domain

import cats.syntax.all._
import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}

import java.util.UUID

/** Opaque cart identifier, minted inside `F` and parsed once at the HTTP
  * boundary (parse, don't validate - scala-fp.md law 5).
  */
opaque type CartId = UUID

object CartId {

  def parse(raw: String): Either[String, CartId] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(raw))
      .leftMap(_ => s"Invalid cart id: '$raw'")

  def fromUuid(value: UUID): CartId = value

  def uuid(value: CartId): UUID = value

  def render(value: CartId): String = value.toString

  given Codec[String, CartId, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw =>
      parse(raw) match {
        case Right(id) => DecodeResult.Value(id)
        case Left(msg) =>
          DecodeResult.Error(raw, new IllegalArgumentException(msg))
      }
    )(render)

  given Schema[CartId] = Schema.string[CartId]
}
