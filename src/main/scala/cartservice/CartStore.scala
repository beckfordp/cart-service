package cartservice

import cats.effect.{Async, Ref, Resource, Sync}
import cats.syntax.all._
import cartservice.domain.CartId
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.{Log, MkRedis}
import dev.profunktor.redis4cats.log4cats.log4CatsInstance
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{FiniteDuration, SECONDS}

final case class Cart(
    id: CartId,
    items: Map[String, Int],
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

trait CartStore[F[_]] {
  def create(): F[Cart]
  def get(id: CartId): F[Option[Cart]]
  def addItem(id: CartId, sku: String, quantity: Int): F[Option[Cart]]
  def removeItem(id: CartId, sku: String): F[Option[Cart]]
  def setQuantity(id: CartId, sku: String, quantity: Int): F[Option[Cart]]
  def delete(id: CartId): F[Boolean]
  def ping: F[Boolean]
}

object CartStore {

  def inMemory[F[_]: Sync]: F[CartStore[F]] =
    Ref.of[F, Map[CartId, Cart]](Map.empty).map { ref =>
      new CartStore[F] {
        def create(): F[Cart] =
          for {
            id <- Sync[F].delay(
              CartId.fromUuid(java.util.UUID.randomUUID())
            )
            now <- Sync[F].realTimeInstant
            entity = Cart(id, Map.empty, now, now)
            _ <- ref.update(_ + (id -> entity))
          } yield entity

        def get(id: CartId): F[Option[Cart]] = ref.get.map(_.get(id))

        def addItem(id: CartId, sku: String, quantity: Int): F[Option[Cart]] =
          for {
            now <- Sync[F].realTimeInstant
            updated <- ref.modify { entities =>
              entities.get(id) match {
                case None           => (entities, None)
                case Some(existing) =>
                  val newQuantity = existing.items.getOrElse(sku, 0) + quantity
                  val next = existing.copy(
                    items = existing.items + (sku -> newQuantity),
                    updatedAt = now
                  )
                  (entities + (id -> next), Some(next))
              }
            }
          } yield updated

        def removeItem(id: CartId, sku: String): F[Option[Cart]] =
          for {
            now <- Sync[F].realTimeInstant
            updated <- ref.modify { entities =>
              entities.get(id) match {
                case None           => (entities, None)
                case Some(existing) =>
                  val next =
                    existing.copy(items = existing.items - sku, updatedAt = now)
                  (entities + (id -> next), Some(next))
              }
            }
          } yield updated

        def setQuantity(
            id: CartId,
            sku: String,
            quantity: Int
        ): F[Option[Cart]] =
          for {
            now <- Sync[F].realTimeInstant
            updated <- ref.modify { entities =>
              entities.get(id) match {
                case None           => (entities, None)
                case Some(existing) =>
                  val newItems =
                    if (quantity <= 0) existing.items - sku
                    else existing.items + (sku -> quantity)
                  val next = existing.copy(items = newItems, updatedAt = now)
                  (entities + (id -> next), Some(next))
              }
            }
          } yield updated

        def delete(id: CartId): F[Boolean] =
          ref.modify { entities =>
            if (entities.contains(id)) (entities - id, true)
            else (entities, false)
          }

        def ping: F[Boolean] = Sync[F].pure(true)
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
                id <- Sync[F].delay(
                  CartId.fromUuid(UUID.randomUUID())
                )
                now <- Sync[F].realTimeInstant
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
              } yield Cart(id, Map.empty, now, now)

            def get(id: CartId): F[Option[Cart]] = getCart(id)

            def addItem(
                id: CartId,
                sku: String,
                quantity: Int
            ): F[Option[Cart]] =
              mutateIfExists(id, "hincrby") {
                commands.hIncrBy(keyFor(id), sku, quantity.toLong).void
              }

            def removeItem(id: CartId, sku: String): F[Option[Cart]] =
              mutateIfExists(id, "hdel") {
                commands.hDel(keyFor(id), sku).void
              }

            def setQuantity(
                id: CartId,
                sku: String,
                quantity: Int
            ): F[Option[Cart]] =
              if (quantity <= 0)
                mutateIfExists(id, "hdel") {
                  commands.hDel(keyFor(id), sku).void
                }
              else
                mutateIfExists(id, "hset") {
                  commands.hSet(keyFor(id), sku, quantity.toString).void
                }

            def delete(id: CartId): F[Boolean] =
              timed("del")(commands.del(keyFor(id)).map(_ > 0))

            def ping: F[Boolean] =
              timed("exists")(commands.exists("__cart_ping__")).attempt
                .map(_.isRight)
          }
        }
    }
  }
}
