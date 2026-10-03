package cartservice

import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class CartResponse(
    id: String,
    items: Map[String, Int],
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

object CartResponse {
  implicit val codec: Codec[CartResponse] = deriveCodec

  def apply(entity: Cart): CartResponse =
    CartResponse(entity.id, entity.items, entity.createdAt, entity.updatedAt)
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object CartRoutes {

  private val createCartEndpoint
      : PublicEndpoint[Unit, Unit, CartResponse, Any] =
    endpoint.post
      .in("carts")
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[CartResponse])

  private val notFoundOutput: EndpointOutput[CartError] =
    statusCode(StatusCode.NotFound)
      .and(jsonBody[ErrorResponse])
      .map[CartError](_ => CartNotFound)(_ => ErrorResponse("Cart not found"))

  private[cartservice] val invalidQuantityOutput: EndpointOutput[CartError] =
    statusCode(StatusCode.BadRequest)
      .and(jsonBody[ErrorResponse])
      .map[CartError](_ => InvalidQuantity)(_ =>
        ErrorResponse("Invalid quantity")
      )

  private val getCartEndpoint: PublicEndpoint[
    String,
    CartError,
    CartResponse,
    Any
  ] =
    endpoint.get
      .in("carts" / path[String]("id"))
      .out(jsonBody[CartResponse])
      .errorOut(notFoundOutput)

  private val deleteCartEndpoint: PublicEndpoint[String, CartError, Unit, Any] =
    endpoint.delete
      .in("carts" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)

  def serverEndpoint[F[_]: Async](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    createCartEndpoint.serverLogicSuccess[F] { _ =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/carts"
          )
        )("Received request")
        entity <- store
          .create()
          .onError { case error =>
            logger.error(Map.empty, error)("Persisting the cart failed")
          }
        _ <- logger.info(
          Map("cart_id" -> entity.id)
        )("Request completed")
      } yield CartResponse(entity)
    }

  def getCartServerEndpoint[F[_]: Async](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getCartEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map("method" -> "GET", "path" -> s"/carts/$id", "cart_id" -> id)
        )(
          "Received request"
        )
        result <- store.get(id).flatMap {
          case Some(entity) =>
            logger
              .info(Map("cart_id" -> id))("Request completed")
              .as(Right(CartResponse(entity)))
          case None =>
            logger
              .warn(Map("cart_id" -> id))("Cart not found")
              .as(Left(CartNotFound))
        }
      } yield result
    }

  def deleteCartServerEndpoint[F[_]: Async](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    deleteCartEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map("method" -> "DELETE", "path" -> s"/carts/$id", "cart_id" -> id)
        )("Received request")
        result <- store.delete(id).flatMap {
          case true =>
            logger
              .info(Map("cart_id" -> id))("Request completed")
              .as(Right(()))
          case false =>
            logger
              .warn(Map("cart_id" -> id))("Cart not found")
              .as(Left(CartNotFound))
        }
      } yield result
    }

  def routes[F[_]: Async](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        serverEndpoint(store, logger),
        getCartServerEndpoint(store, logger),
        deleteCartServerEndpoint(store, logger)
      )
    )
}
