package cartservice

import cats.effect.{Ref, Sync}
import cats.syntax.all._

final case class Cart(
    id: String,
    items: Map[String, Int],
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

trait CartStore[F[_]] {
  def create(): F[Cart]
  def get(id: String): F[Option[Cart]]
  def addItem(id: String, sku: String, quantity: Int): F[Option[Cart]]
  def removeItem(id: String, sku: String): F[Option[Cart]]
  def setQuantity(id: String, sku: String, quantity: Int): F[Option[Cart]]
  def delete(id: String): F[Boolean]
  def ping: F[Boolean]
}

object CartStore {

  def inMemory[F[_]: Sync]: F[CartStore[F]] =
    Ref.of[F, Map[String, Cart]](Map.empty).map { ref =>
      new CartStore[F] {
        def create(): F[Cart] =
          for {
            id <- Sync[F].delay(java.util.UUID.randomUUID().toString)
            now <- Sync[F].realTimeInstant
            entity = Cart(id, Map.empty, now, now)
            _ <- ref.update(_ + (id -> entity))
          } yield entity

        def get(id: String): F[Option[Cart]] = ref.get.map(_.get(id))

        def addItem(id: String, sku: String, quantity: Int): F[Option[Cart]] =
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

        def removeItem(id: String, sku: String): F[Option[Cart]] =
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
            id: String,
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

        def delete(id: String): F[Boolean] =
          ref.modify { entities =>
            if (entities.contains(id)) (entities - id, true)
            else (entities, false)
          }

        def ping: F[Boolean] = Sync[F].pure(true)
      }
    }
}
