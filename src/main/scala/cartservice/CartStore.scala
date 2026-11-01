package cartservice

import cats.effect.{Async, Clock, Concurrent, Ref, Resource}
import cats.effect.std.UUIDGen
import cats.syntax.all._
import cartservice.domain.{
  Cart,
  CartError,
  CartId,
  CartNotFound,
  InvalidQuantity,
  Quantity,
  Sku
}
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.{Log, MkRedis}
import dev.profunktor.redis4cats.effects.ScriptOutputType
import dev.profunktor.redis4cats.log4cats.log4CatsInstance
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.LatencyBuckets

import java.time.Instant
import java.time.format.DateTimeParseException
import scala.concurrent.duration.SECONDS

trait CartStore[F[_]] {

  /** Persists a new empty cart with a generated id and its TTL. */
  def create(): F[Cart]

  /** `None` when no cart exists (never created, deleted, or expired). */
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

  /** `CartNotFound` when the cart was already absent. */
  def delete(id: CartId): F[Either[CartNotFound.type, Unit]]

  /** Whether the backing store answers at all (readiness probe). */
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

  private def keyFor(id: CartId): String = s"cart:${CartId.render(id)}"

  private def instantField(
      fields: Map[String, String],
      name: String
  ): Either[String, Instant] =
    fields
      .get(name)
      .toRight(s"missing $name")
      .flatMap(raw =>
        Either
          .catchOnly[DateTimeParseException](Instant.parse(raw))
          .leftMap(_ => s"$name is not an instant: '$raw'")
      )

  private[cartservice] def parseCart(
      id: CartId,
      fields: Map[String, String]
  ): Either[String, Cart] =
    for {
      createdAt <- instantField(fields, createdAtField)
      updatedAt <- instantField(fields, updatedAtField)
      items <- (fields - createdAtField - updatedAtField).toList
        .traverse { case (sku, raw) =>
          raw.toIntOption
            .toRight(s"quantity of '$sku' is not an Int: '$raw'")
            .map(sku -> _)
        }
    } yield Cart(id, items.toMap, createdAt, updatedAt)

  /** Every write is one Lua script, so Redis runs it atomically: no partial
    * write, no TTL-less key, and no resurrecting a cart deleted (or expired)
    * mid-operation. ARGV[1] = updatedAt field, ARGV[2] = now, ARGV[3] = TTL
    * seconds, then the operation's own arguments. Each returns `{'missing'}`,
    * `{'limit'}` or `{'ok', field1, value1, ...}` (the whole hash after the
    * write) - never nil, which redis4cats' `Multi` output can't represent.
    */
  private def mutationScript(write: String): String =
    s"""if redis.call('EXISTS', KEYS[1]) == 0 then return {'missing'} end
       |$write
       |redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
       |redis.call('EXPIRE', KEYS[1], ARGV[3])
       |local fields = redis.call('HGETALL', KEYS[1])
       |table.insert(fields, 1, 'ok')
       |return fields""".stripMargin

  /** ARGV[4] = sku, ARGV[5] = quantity to add, ARGV[6] = the core's
    * [[Quantity.Max]]: the line limit is checked atomically here, but its value
    * comes from the pure core.
    */
  private val addItemScript: String = mutationScript(
    """local total = tonumber(redis.call('HGET', KEYS[1], ARGV[4]) or '0') + tonumber(ARGV[5])
      |if total > tonumber(ARGV[6]) then return {'limit'} end
      |redis.call('HSET', KEYS[1], ARGV[4], tostring(total))""".stripMargin
  )

  /** ARGV[4] = sku. */
  private val removeItemScript: String =
    mutationScript("redis.call('HDEL', KEYS[1], ARGV[4])")

  /** ARGV[4] = sku, ARGV[5] = quantity. */
  private val setQuantityScript: String =
    mutationScript("redis.call('HSET', KEYS[1], ARGV[4], ARGV[5])")

  /** ARGV[1] = createdAt field, ARGV[2] = updatedAt field, ARGV[3] = now,
    * ARGV[4] = TTL seconds: the hash and its TTL land together.
    */
  private val createScript: String =
    """redis.call('HSET', KEYS[1], ARGV[1], ARGV[3], ARGV[2], ARGV[3])
      |redis.call('EXPIRE', KEYS[1], ARGV[4])
      |return {'ok'}""".stripMargin

  private enum Mutation {
    case Applied(fields: Map[String, String])
    case Missing
    case OverLimit
  }

  private def mutationResult(
      id: CartId,
      reply: List[String]
  ): Either[String, Mutation] =
    reply match {
      case "ok" :: fields =>
        val pairs = fields.grouped(2).toList
        pairs
          .traverse {
            case List(field, value) => Right(field -> value)
            case odd                => Left(s"odd HGETALL reply: $odd")
          }
          .map(kvs => Mutation.Applied(kvs.toMap))
      case List("missing") => Right(Mutation.Missing)
      case List("limit")   => Right(Mutation.OverLimit)
      case other           =>
        Left(s"unexpected script reply for ${CartId.render(id)}: $other")
    }

  def redis[F[_]: Async](
      config: RedisConfig,
      meter: Meter[F],
      logger: StructuredLogger[F]
  ): Resource[F, CartStore[F]] = {
    given Log[F] = log4CatsInstance(using logger)
    given MkRedis[F] = MkRedis.forAsync[F]
    val ttlSeconds = config.ttlSeconds.toString

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

          def toCart(id: CartId, fields: Map[String, String]): F[Cart] =
            parseCart(id, fields).leftMap(CorruptCart(id, _)).liftTo[F]

          /** Runs a mutation script and parses its reply. */
          def run(id: CartId, operation: String, script: String)(
              args: String*
          ): F[Mutation] =
            Clock[F].realTimeInstant.flatMap { now =>
              timed(operation) {
                commands.eval(
                  script,
                  ScriptOutputType.Multi[String],
                  List(keyFor(id)),
                  List(updatedAtField, now.toString, ttlSeconds) ++ args
                )
              }.flatMap(
                mutationResult(id, _).leftMap(CorruptCart(id, _)).liftTo[F]
              )
            }

          /** For the scripts that never return `limit` (only add does). */
          def mutateExisting(id: CartId, operation: String, script: String)(
              args: String*
          ): F[Either[CartNotFound.type, Cart]] =
            run(id, operation, script)(args*).flatMap {
              case Mutation.Applied(fields) => toCart(id, fields).map(Right(_))
              case Mutation.Missing         => Async[F].pure(Left(CartNotFound))
              case Mutation.OverLimit       =>
                Async[F].raiseError(
                  CorruptCart(id, s"unexpected limit reply to $operation")
                )
            }

          new CartStore[F] {
            def create(): F[Cart] =
              for {
                id <- UUIDGen[F].randomUUID.map(CartId.fromUuid)
                now <- Clock[F].realTimeInstant
                _ <- timed("create") {
                  commands.eval(
                    createScript,
                    ScriptOutputType.Multi[String],
                    List(keyFor(id)),
                    List(
                      createdAtField,
                      updatedAtField,
                      now.toString,
                      ttlSeconds
                    )
                  )
                }
              } yield Cart.empty(id, now)

            def get(id: CartId): F[Option[Cart]] =
              timed("hgetall")(commands.hGetAll(keyFor(id))).flatMap { fields =>
                if (fields.isEmpty) Async[F].pure(None)
                else toCart(id, fields).map(Some(_))
              }

            def addItem(
                id: CartId,
                sku: Sku,
                quantity: Quantity
            ): F[Either[CartError, Cart]] =
              run(id, "add_item", addItemScript)(
                Sku.value(sku),
                Quantity.value(quantity).toString,
                Quantity.Max.toString
              ).flatMap {
                case Mutation.Applied(fields) =>
                  toCart(id, fields).map(Right(_))
                case Mutation.Missing   => Async[F].pure(Left(CartNotFound))
                case Mutation.OverLimit => Async[F].pure(Left(InvalidQuantity))
              }

            def removeItem(
                id: CartId,
                sku: Sku
            ): F[Either[CartNotFound.type, Cart]] =
              mutateExisting(id, "remove_item", removeItemScript)(
                Sku.value(sku)
              )

            def setQuantity(
                id: CartId,
                sku: Sku,
                target: Option[Quantity]
            ): F[Either[CartNotFound.type, Cart]] =
              target match {
                case None           => removeItem(id, sku)
                case Some(quantity) =>
                  mutateExisting(id, "set_quantity", setQuantityScript)(
                    Sku.value(sku),
                    Quantity.value(quantity).toString
                  )
              }

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

/** A stored cart that no longer parses (bad timestamp or quantity) - an
  * environment failure, surfaced as a 500, never read as a missing cart.
  */
final case class CorruptCart(id: CartId, reason: String)
    extends RuntimeException(
      s"Cart ${CartId.render(id)} is corrupt in Redis: $reason"
    )
