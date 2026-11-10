// SPDX-License-Identifier: MIT

package cartservice

import cats.{Monad, MonadThrow}
import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import cartservice.domain.{
  Cart,
  CartError,
  CartId,
  CartNotFound,
  InvalidQuantity,
  InvalidSku,
  Quantity,
  Sku
}
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.Schema.annotations.{description, encodedExample}
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

@description("A cart with its line items and timestamps.")
final case class CartResponse(
    @description("Cart id (UUID).")
    @encodedExample("3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90")
    id: String,
    @description(
      "Line items: sku -> quantity (1..999 per line); empty for a new cart."
    )
    items: Map[String, Int],
    @description("When the cart was created (UTC).")
    @encodedExample("2026-11-01T09:30:00Z")
    createdAt: java.time.Instant,
    @description("When the cart last changed (UTC); a read does not bump it.")
    @encodedExample("2026-11-01T09:45:00Z")
    updatedAt: java.time.Instant
)

object CartResponse {
  implicit val codec: Codec[CartResponse] = deriveCodec

  def apply(entity: Cart): CartResponse =
    CartResponse(
      CartId.render(entity.id),
      entity.items,
      entity.createdAt,
      entity.updatedAt
    )
}

@description("Error body.")
final case class ErrorResponse(
    @description("Human-readable reason.") @encodedExample("Cart not found")
    error: String
)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

@description("One line to add to a cart.")
final case class AddItemRequest(
    @description(
      "Catalog sku; non-blank, at most 128 chars, never starting with `__` (reserved for cart metadata)."
    )
    @encodedExample("SKU-MUG-001")
    sku: String,
    @description(
      "Units to add, 1..999; an add that would take the line past 999 is rejected."
    )
    @encodedExample(2)
    quantity: Int
)

object AddItemRequest {
  implicit val codec: Codec[AddItemRequest] = deriveCodec
}

object CartRoutes {

  // REST docs convention (purerest README "Documenting endpoints"): every
  // endpoint, parameter, DTO field and error is described, and CartDocsSuite
  // asserts Docs.undocumented finds no gaps.
  private val tag = "carts"

  // Each fixed error's body, shared by its documented example and its real
  // mapping.
  private val notFound = ErrorResponse("Cart not found")
  private val invalidQuantity = ErrorResponse("Invalid quantity")
  private val invalidSku = ErrorResponse("Invalid sku")

  private val exampleCart = CartResponse(
    "3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90",
    Map("SKU-MUG-001" -> 2),
    java.time.Instant.parse("2026-11-01T09:30:00Z"),
    java.time.Instant.parse("2026-11-01T09:45:00Z")
  )

  private def errorBody(
      what: String,
      example: ErrorResponse
  ): EndpointIO.Body[String, ErrorResponse] =
    jsonBody[ErrorResponse].description(what).example(example)

  private val cartBody: EndpointIO.Body[String, CartResponse] =
    jsonBody[CartResponse].example(exampleCart)

  private val idPath: EndpointInput.PathCapture[CartId] =
    path[CartId]("id")
      .description("Cart id (UUID). A malformed id returns 400.")
      .example(
        CartId.fromUuid(
          java.util.UUID.fromString("3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90")
        )
      )

  private val skuPath: EndpointInput.PathCapture[Sku] =
    path[Sku]("sku")
      .description(
        "Catalog sku: non-blank, at most 128 chars, never starting with `__` (reserved for cart metadata)."
      )
      .example(Sku.Example)

  private val createCartEndpoint
      : PublicEndpoint[Unit, Unit, CartResponse, Any] =
    endpoint.post
      .in("carts")
      .out(statusCode(StatusCode.Created))
      .out(cartBody)
      .summary("Create a cart")
      .description(
        "Creates an empty cart with a server-generated UUID and a 24h TTL (refreshed by every write). Not idempotent: each call is a new cart. No request body."
      )
      .tag(tag)

  private val notFoundOutput: EndpointOutput[CartError] =
    statusCode(StatusCode.NotFound)
      .and(errorBody("No cart with that id.", notFound))
      .map[CartError](_ => CartNotFound)(_ => notFound)

  private[cartservice] val invalidQuantityOutput: EndpointOutput[CartError] =
    statusCode(StatusCode.BadRequest)
      .and(
        errorBody(
          "Quantity outside 1..999, or an add that would take the line past 999.",
          invalidQuantity
        )
      )
      .map[CartError](_ => InvalidQuantity)(_ => invalidQuantity)

  private val invalidSkuOutput: EndpointOutput[CartError] =
    statusCode(StatusCode.BadRequest)
      .and(
        errorBody(
          "Sku blank, over 128 chars, or starting with the reserved `__` prefix.",
          invalidSku
        )
      )
      .map[CartError](_ => InvalidSku)(_ => invalidSku)

  private val getCartEndpoint: PublicEndpoint[
    CartId,
    CartError,
    CartResponse,
    Any
  ] =
    endpoint.get
      .in("carts" / idPath)
      .out(cartBody)
      .errorOut(notFoundOutput)
      .summary("Get a cart")
      .description(
        "One cart with its line items and timestamps. 404 when no such cart exists (never created, deleted, or expired by its TTL)."
      )
      .tag(tag)

  private val deleteCartEndpoint: PublicEndpoint[CartId, CartError, Unit, Any] =
    endpoint.delete
      .in("carts" / idPath)
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)
      .summary("Delete a cart")
      .description(
        "Hard-deletes the cart and every line. Deleting an already-absent cart → 404."
      )
      .tag(tag)

  private val addItemErrorOutput: EndpointOutput[CartError] =
    oneOf[CartError](
      oneOfVariantValueMatcher(invalidQuantityOutput) { case InvalidQuantity =>
        true
      },
      oneOfVariantValueMatcher(invalidSkuOutput) { case InvalidSku => true },
      oneOfVariantValueMatcher(notFoundOutput) { case CartNotFound => true }
    )

  private val addItemEndpoint: PublicEndpoint[
    (CartId, AddItemRequest),
    CartError,
    CartResponse,
    Any
  ] =
    endpoint.post
      .in("carts" / idPath / "items")
      .in(jsonBody[AddItemRequest].example(AddItemRequest("SKU-MUG-001", 2)))
      .out(cartBody)
      .errorOut(addItemErrorOutput)
      .summary("Add an item")
      .description(
        "Adds the sku's line, or increments it by `quantity`. The body is validated before the cart lookup: a blank/over-long/reserved sku or a quantity outside 1..999 → 400, and an add that would take an existing line past 999 → 400, leaving the cart unchanged. Not idempotent: each call increments. 404 when the cart doesn't exist."
      )
      .tag(tag)

  private val removeItemEndpoint: PublicEndpoint[
    (CartId, Sku),
    CartError,
    CartResponse,
    Any
  ] =
    endpoint.delete
      .in("carts" / idPath / "items" / skuPath)
      .out(cartBody)
      .errorOut(notFoundOutput)
      .summary("Remove an item")
      .description(
        "Drops the sku's line entirely, whatever its quantity; a sku not in the cart is a no-op returning the unchanged cart. The path sku is validated like a body sku (blank/over-long/reserved `__` prefix is a tapir 400 before the handler). 404 when the cart doesn't exist."
      )
      .tag(tag)

  def serverEndpoint[F[_]: MonadThrow](
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
          Map("cart_id" -> CartId.render(entity.id))
        )("Request completed")
      } yield CartResponse(entity)
    }

  def getCartServerEndpoint[F[_]: Monad](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getCartEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "GET",
            "path" -> s"/carts/$id",
            "cart_id" -> CartId.render(id)
          )
        )(
          "Received request"
        )
        result <- store.get(id).flatMap {
          case Some(entity) =>
            logger
              .info(Map("cart_id" -> CartId.render(id)))("Request completed")
              .as(Right(CartResponse(entity)))
          case None =>
            logger
              .warn(Map("cart_id" -> CartId.render(id)))("Cart not found")
              .as(Left(CartNotFound))
        }
      } yield result
    }

  def deleteCartServerEndpoint[F[_]: Monad](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    deleteCartEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "DELETE",
            "path" -> s"/carts/$id",
            "cart_id" -> CartId.render(id)
          )
        )("Received request")
        result <- store.delete(id).flatMap {
          case Right(()) =>
            logger
              .info(Map("cart_id" -> CartId.render(id)))("Request completed")
              .as(Right(()))
          case Left(CartNotFound) =>
            logger
              .warn(Map("cart_id" -> CartId.render(id)))("Cart not found")
              .as(Left(CartNotFound))
        }
      } yield result
    }

  def addItemServerEndpoint[F[_]: Monad](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    addItemEndpoint.serverLogic[F] { case (id, req) =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> s"/carts/$id/items",
            "cart_id" -> CartId.render(id)
          )
        )("Received request")
        result <- (
          for {
            sku <- Sku.parse(req.sku)
            quantity <- Quantity.parse(req.quantity)
          } yield (sku, quantity)
        ) match {
          case Left(error) =>
            logger
              .warn(Map("cart_id" -> CartId.render(id)))(
                s"Rejected item: $error"
              )
              .as(Left(error))
          case Right((sku, quantity)) =>
            store.addItem(id, sku, quantity).flatMap {
              case Right(entity) =>
                logger
                  .info(Map("cart_id" -> CartId.render(id)))(
                    "Request completed"
                  )
                  .as(Right(CartResponse(entity)))
              case Left(error) =>
                logger
                  .warn(Map("cart_id" -> CartId.render(id)))(
                    s"Item not added: $error"
                  )
                  .as(Left(error))
            }
        }
      } yield result
    }

  def removeItemServerEndpoint[F[_]: Monad](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    removeItemEndpoint.serverLogic[F] { case (id, sku) =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "DELETE",
            "path" -> s"/carts/$id/items/${Sku.value(sku)}",
            "cart_id" -> CartId.render(id)
          )
        )("Received request")
        result <- store.removeItem(id, sku).flatMap {
          case Right(entity) =>
            logger
              .info(Map("cart_id" -> CartId.render(id)))("Request completed")
              .as(Right(CartResponse(entity)))
          case Left(CartNotFound) =>
            logger
              .warn(Map("cart_id" -> CartId.render(id)))("Cart not found")
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
        addItemServerEndpoint(store, logger),
        removeItemServerEndpoint(store, logger),
        deleteCartServerEndpoint(store, logger)
      )
    )
}
