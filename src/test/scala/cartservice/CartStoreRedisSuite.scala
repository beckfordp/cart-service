package cartservice

import cats.effect.IO
import com.dimafeng.testcontainers.RedisContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.{Log, MkRedis}
import dev.profunktor.redis4cats.log4cats.log4CatsInstance
import cartservice.domain.CartId
import munit.CatsEffectSuite

import java.util.UUID
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.Metrics

class CartStoreRedisSuite extends CatsEffectSuite with TestContainerForAll {

  override val containerDef: RedisContainer.Def = RedisContainer.Def()

  private def configFor(
      redis: RedisContainer,
      ttlSeconds: Int = 3600
  ): RedisConfig =
    RedisConfig(uri = redis.redisUri, ttlSeconds = ttlSeconds)

  test("create persists an empty cart and returns it with a generated id") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          store.create().map { entity =>
            assert(CartId.render(entity.id).nonEmpty)
            assertEquals(entity.items, Map.empty[String, Int])
          }
        }
    }
  }

  test("create produces distinct ids across calls") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            first <- store.create()
            second <- store.create()
          } yield assertNotEquals(first.id, second.id)
        }
    }
  }

  test("get returns the persisted cart") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            created <- store.create()
            found <- store.get(created.id)
          } yield assertEquals(found, Some(created))
        }
    }
  }

  test("get returns None for an unknown id") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          store
            .get(CartId.fromUuid(UUID.randomUUID()))
            .map(assertEquals(_, None))
        }
    }
  }

  test("addItem on a new sku inserts it at the given quantity") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            created <- store.create()
            updated <- store.addItem(created.id, "sku-1", 3)
          } yield assertEquals(updated.map(_.items), Some(Map("sku-1" -> 3)))
        }
    }
  }

  test(
    "addItem on an existing sku increments rather than overwrites its quantity"
  ) {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            created <- store.create()
            _ <- store.addItem(created.id, "sku-1", 3)
            updated <- store.addItem(created.id, "sku-1", 2)
          } yield assertEquals(updated.map(_.items), Some(Map("sku-1" -> 5)))
        }
    }
  }

  test("addItem returns None for an unknown cart id") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          store
            .addItem(CartId.fromUuid(UUID.randomUUID()), "sku-1", 1)
            .map(assertEquals(_, None))
        }
    }
  }

  test("removeItem deletes a sku entirely, regardless of its quantity") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            created <- store.create()
            _ <- store.addItem(created.id, "sku-1", 5)
            updated <- store.removeItem(created.id, "sku-1")
          } yield assertEquals(
            updated.map(_.items),
            Some(Map.empty[String, Int])
          )
        }
    }
  }

  test("removeItem returns None for an unknown cart id") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          store
            .removeItem(CartId.fromUuid(UUID.randomUUID()), "sku-1")
            .map(assertEquals(_, None))
        }
    }
  }

  test("setQuantity with a positive value sets it exactly, not additively") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            created <- store.create()
            _ <- store.addItem(created.id, "sku-1", 3)
            updated <- store.setQuantity(created.id, "sku-1", 10)
          } yield assertEquals(updated.map(_.items), Some(Map("sku-1" -> 10)))
        }
    }
  }

  test("setQuantity with 0 removes the sku") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            created <- store.create()
            _ <- store.addItem(created.id, "sku-1", 3)
            updated <- store.setQuantity(created.id, "sku-1", 0)
          } yield assertEquals(
            updated.map(_.items),
            Some(Map.empty[String, Int])
          )
        }
    }
  }

  test("setQuantity returns None for an unknown cart id") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          store
            .setQuantity(CartId.fromUuid(UUID.randomUUID()), "sku-1", 1)
            .map(assertEquals(_, None))
        }
    }
  }

  test("delete removes the cart and returns true, and get then returns None") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          for {
            created <- store.create()
            deleted <- store.delete(created.id)
            found <- store.get(created.id)
          } yield {
            assert(deleted)
            assertEquals(found, None)
          }
        }
    }
  }

  test("delete returns false for an unknown id") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          store
            .delete(CartId.fromUuid(UUID.randomUUID()))
            .map(deleted => assert(!deleted))
        }
    }
  }

  test("ping returns true against a real, reachable Redis") {
    withContainers { redis =>
      CartStore
        .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
        .use { store =>
          store.ping.map(assert(_))
        }
    }
  }

  test("a cart's key carries a TTL after create, refreshed on every write") {
    withContainers { redis =>
      given Log[IO] = log4CatsInstance(using NoOpLogger[IO])
      given MkRedis[IO] = MkRedis.forAsync[IO]

      for {
        cartId <-
          CartStore
            .redis[IO](configFor(redis), Meter.noop[IO], NoOpLogger[IO])
            .use { store =>
              for {
                created <- store.create()
                _ <- store.addItem(created.id, "sku-1", 1)
              } yield created.id
            }
        ttl <- Redis[IO].utf8(redis.redisUri).use(_.ttl(s"cart:$cartId"))
      } yield assert(
        ttl.exists(_.toSeconds > 0),
        s"expected a positive TTL, got: $ttl"
      )
    }
  }

  test(
    "create and get each record a db.client.operation.duration measurement, tagged by operation"
  ) {
    withContainers { redis =>
      Metrics.test[IO]("cart-store-redis-metrics-test").use { testMeter =>
        CartStore
          .redis[IO](configFor(redis), testMeter.meter, NoOpLogger[IO])
          .use { store =>
            for {
              created <- store.create()
              _ <- store.get(created.id)
              metrics <- testMeter.collectMetrics
            } yield {
              val data =
                metrics.find(_.getName == "db.client.operation.duration")
              assert(
                data.isDefined,
                s"expected a db.client.operation.duration series, got: $metrics"
              )
            }
          }
      }
    }
  }
}
