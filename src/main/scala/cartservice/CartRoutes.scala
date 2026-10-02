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

final case class CreateCartRequest( /* codegen:fields:CREATE_PARAMS */ )

object CreateCartRequest {
  implicit val codec: Codec[CreateCartRequest] = deriveCodec
}

final case class UpdateCartRequest( /* codegen:fields:UPDATE_PARAMS */ )

object UpdateCartRequest {
  implicit val codec: Codec[UpdateCartRequest] = deriveCodec
}

final case class CartResponse(
    id: String,
    // codegen:fields:CASE_CLASS_FIELD
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

object CartResponse {
  implicit val codec: Codec[CartResponse] = deriveCodec

  def apply(entity: Cart): CartResponse =
    CartResponse(
      entity.id,
      /* codegen:fields:RESPONSE_APPLY_ARGS */
      entity.createdAt,
      entity.updatedAt
    )
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object CartRoutes {

  private val createCartEndpoint: PublicEndpoint[
    CreateCartRequest,
    Unit,
    CartResponse,
    Any
  ] =
    endpoint.post
      .in("carts")
      .in(jsonBody[CreateCartRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[CartResponse])

  private val notFoundOutput: EndpointOutput[CartError] =
    statusCode(StatusCode.NotFound)
      .and(jsonBody[ErrorResponse])
      .map[CartError](_ => CartNotFound)(_ => ErrorResponse("Cart not found"))

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

  private val updateCartEndpoint: PublicEndpoint[
    (String, UpdateCartRequest),
    CartError,
    CartResponse,
    Any
  ] =
    endpoint.patch
      .in("carts" / path[String]("id"))
      .in(jsonBody[UpdateCartRequest])
      .out(jsonBody[CartResponse])
      .errorOut(notFoundOutput)

  private val replaceCartEndpoint: PublicEndpoint[
    (String, UpdateCartRequest),
    CartError,
    CartResponse,
    Any
  ] =
    endpoint.put
      .in("carts" / path[String]("id"))
      .in(jsonBody[UpdateCartRequest])
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
    createCartEndpoint.serverLogicSuccess[F] { req =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/carts"
          )
        )("Received request")
        entity <- store
          .create( /* codegen:fields:CREATE_CALL_ARGS */ )
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

  /** Shared handler for `PATCH` (partial update) and `PUT` (full replace) —
    * both call `CartStore.update` with the same required update body; only the
    * logged HTTP method differs.
    */
  private def updateLogic[F[_]: Async](
      store: CartStore[F],
      logger: StructuredLogger[F],
      httpMethod: String
  )(
      id: String,
      req: UpdateCartRequest
  ): F[Either[CartError, CartResponse]] =
    for {
      _ <- logger.info(
        Map(
          "method" -> httpMethod,
          "path" -> s"/carts/$id",
          "cart_id" -> id
        )
      )("Received request")
      result <- store
        .update(id /* codegen:fields:UPDATE_CALL_ARGS */ )
        .flatMap {
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

  def updateCartServerEndpoint[F[_]: Async](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    updateCartEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PATCH")(id, req)
    }

  def replaceCartServerEndpoint[F[_]: Async](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    replaceCartEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PUT")(id, req)
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
        updateCartServerEndpoint(store, logger),
        replaceCartServerEndpoint(store, logger),
        deleteCartServerEndpoint(store, logger)
      )
    )
}
