package cartservice

import cats.effect.{Async, Clock, Concurrent, Ref, Resource, Sync}
import cats.effect.std.UUIDGen
import cats.syntax.all._
import cartservice.domain.{Cart, CartError, CartId, CartNotFound, Quantity, Sku}
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.{Log, MkRedis}
import dev.profunktor.redis4cats.log4cats.log4CatsInstance
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.LatencyBuckets

import java.time.Instant
import scala.concurrent.duration.{FiniteDuration, SECONDS}

trait CartStore[F[_]] {
  def create(): F[Cart]
  def get(id: CartId): F[Option[Cart]]

  /** `InvalidQuantity` when the line would exceed [[Quantity.Max]]. */
  def addItem(
      id: CartId,
      sku: Sku,
      quantity: Quantity
  ): F[Either[CartError, Cart]]
  def removeItem(id: CartId, sku: Sku): F[Either[CartNotFound.type, Cart]]

  /** `None` removes the line (see [[Quantity.parseTarget]]). */
  def setQuantity(
      id: CartId,
      sku: Sku,
      target: Option[Quantity]
  ): F[Either[CartNotFound.type, Cart]]
  def delete(id: CartId): F[Either[CartNotFound.type, Unit]]
  def ping: F[Boolean]
}

object CartStore {

  def inMemory[F[_]: Concurrent: Clock: UUIDGen]: F[CartStore[F]] =
    Ref.of[F, Map[CartId, Cart]](Map.empty).map { ref =>
      new CartStore[F] {

        /** Applies a pure core operation to an existing cart atomically. */
        private def modify[E >: CartNotFound.type](id: CartId)(
            op: (Cart, Instant) => Either[E, Cart]
        ): F[Either[E, Cart]] =
          Clock[F].realTimeInstant.flatMap { now =>
            ref.modify { carts =>
              carts.get(id).toRight[E](CartNotFound).flatMap(op(_, now)) match {
                case Right(next) => (carts + (id -> next), Right(next))
                case Left(error) => (carts, Left(error))
              }
            }
          }

        def create(): F[Cart] =
          for {
            id <- UUIDGen[F].randomUUID.map(CartId.fromUuid)
            now <- Clock[F].realTimeInstant
            cart = Cart.empty(id, now)
            _ <- ref.update(_ + (id -> cart))
          } yield cart

        def get(id: CartId): F[Option[Cart]] = ref.get.map(_.get(id))

        def addItem(
            id: CartId,
            sku: Sku,
            quantity: Quantity
        ): F[Either[CartError, Cart]] =
          modify(id)(Cart.addItem(_, sku, quantity, _))

        def removeItem(
            id: CartId,
            sku: Sku
        ): F[Either[CartNotFound.type, Cart]] =
          modify(id)((cart, now) => Right(Cart.removeItem(cart, sku, now)))

        def setQuantity(
            id: CartId,
            sku: Sku,
            target: Option[Quantity]
        ): F[Either[CartNotFound.type, Cart]] =
          modify(id)((cart, now) =>
            Right(Cart.setQuantity(cart, sku, target, now))
          )

        def delete(id: CartId): F[Either[CartNotFound.type, Unit]] =
          ref.modify { carts =>
            if (carts.contains(id)) (carts - id, Right(()))
            else (carts, Left(CartNotFound))
          }

        def ping: F[Boolean] = Concurrent[F].pure(true)
      }
    }

  private val createdAtField = "__created_at"
  private val updatedAtField = "__updated_at"

  private def keyFor(id: CartId): String = s"cart:$id"

  private def parseCart(id: CartId, fields: Map[String, String]): Option[Cart] =
    for {
      createdAtStr <- fields.get(createdAtField)
      updatedAtStr <- fields.get(updatedAtField)
      createdAt <- scala.util.Try(Instant.parse(createdAtStr)).toOption
      updatedAt <- scala.util.Try(Instant.parse(updatedAtStr)).toOption
      items <- (fields - createdAtField - updatedAtField)
        .foldLeft(Option(Map.empty[String, Int])) {
          case (acc, (sku, quantity)) =>
            for {
              soFar <- acc
              parsedQuantity <- scala.util.Try(quantity.toInt).toOption
            } yield soFar + (sku -> parsedQuantity)
        }
    } yield Cart(id, items, createdAt, updatedAt)

  def redis[F[_]: Async](
      config: RedisConfig,
      meter: Meter[F],
      logger: StructuredLogger[F]
  ): Resource[F, CartStore[F]] = {
    given Log[F] = log4CatsInstance(using logger)
    given MkRedis[F] = MkRedis.forAsync[F]
    val ttl = FiniteDuration(config.ttlSeconds.toLong, SECONDS)

    Redis[F].utf8(config.uri).evalMap { commands =>
      meter
        .histogram[Double]("db.client.operation.duration")
        .withUnit("s")
        .withExplicitBucketBoundaries(LatencyBuckets.seconds)
        .create
        .map { histogram =>
          def timed[A](operation: String)(fa: F[A]): F[A] =
            for {
              start <- Async[F].monotonic
              result <- fa.attempt
              end <- Async[F].monotonic
              outcomeAttributes = result match {
                case Right(_)    => Nil
                case Left(error) =>
                  List(Attribute("error.type", error.getClass.getName))
              }
              _ <- histogram.record(
                (end - start).toUnit(SECONDS),
                List(
                  Attribute("db.system", "redis"),
                  Attribute("db.operation", operation)
                ) ++ outcomeAttributes
              )
              a <- result.liftTo[F]
            } yield a

          new CartStore[F] {
            private def getCart(id: CartId): F[Option[Cart]] =
              timed("hgetall") {
                commands.hGetAll(keyFor(id)).map { fields =>
                  if (fields.isEmpty) None else parseCart(id, fields)
                }
              }

            private def mutateIfExists(
                id: CartId,
                operation: String
            )(mutate: F[Unit]): F[Option[Cart]] = {
              val key = keyFor(id)
              for {
                existsAlready <- timed("exists")(commands.exists(key))
                result <-
                  if (!existsAlready) Sync[F].pure(None)
                  else
                    for {
                      now <- Sync[F].realTimeInstant
                      _ <- timed(operation)(mutate)
                      _ <- commands.hSet(key, updatedAtField, now.toString)
                      _ <- commands.expire(key, ttl)
                      updated <- getCart(id)
                      // Defensive cleanup: if a concurrent delete raced this
                      // operation (landed between the exists check above and
                      // the hSet touch just now), that hSet would have
                      // resurrected an empty hash with only an updatedAt
                      // field - getCart returns None for it (no createdAt),
                      // but the key would otherwise linger until its TTL
                      // expires. Clean it up immediately instead.
                      _ <-
                        if (updated.isEmpty) commands.del(key).void
                        else Sync[F].unit
                    } yield updated
              } yield result
            }

            def create(): F[Cart] =
              for {
                id <- UUIDGen[F].randomUUID.map(CartId.fromUuid)
                now <- Clock[F].realTimeInstant
                key = keyFor(id)
                _ <- timed("hset") {
                  commands.hSet(
                    key,
                    Map(
                      createdAtField -> now.toString,
                      updatedAtField -> now.toString
                    )
                  )
                }
                _ <- commands.expire(key, ttl)
              } yield Cart.empty(id, now)

            def get(id: CartId): F[Option[Cart]] = getCart(id)

            def addItem(
                id: CartId,
                sku: Sku,
                quantity: Quantity
            ): F[Either[CartError, Cart]] =
              mutateIfExists(id, "hincrby") {
                commands
                  .hIncrBy(
                    keyFor(id),
                    Sku.value(sku),
                    Quantity.value(quantity).toLong
                  )
                  .void
              }.map(_.toRight(CartNotFound))

            def removeItem(
                id: CartId,
                sku: Sku
            ): F[Either[CartNotFound.type, Cart]] =
              mutateIfExists(id, "hdel") {
                commands.hDel(keyFor(id), Sku.value(sku)).void
              }.map(_.toRight(CartNotFound))

            def setQuantity(
                id: CartId,
                sku: Sku,
                target: Option[Quantity]
            ): F[Either[CartNotFound.type, Cart]] =
              (target match {
                case None =>
                  mutateIfExists(id, "hdel") {
                    commands.hDel(keyFor(id), Sku.value(sku)).void
                  }
                case Some(quantity) =>
                  mutateIfExists(id, "hset") {
                    commands
                      .hSet(
                        keyFor(id),
                        Sku.value(sku),
                        Quantity.value(quantity).toString
                      )
                      .void
                  }
              }).map(_.toRight(CartNotFound))

            def delete(id: CartId): F[Either[CartNotFound.type, Unit]] =
              timed("del")(commands.del(keyFor(id))).map { removed =>
                Either.cond(removed > 0, (), CartNotFound)
              }

            def ping: F[Boolean] =
              timed("exists")(commands.exists("__cart_ping__")).attempt
                .map(_.isRight)
          }
        }
    }
  }
}
