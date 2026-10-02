package cartservice

import cats.effect.IO
import munit.CatsEffectSuite

class CartStoreSuite extends CatsEffectSuite {

  test("create returns an empty cart with a generated id") {
    for {
      store <- CartStore.inMemory[IO]
      entity <- store.create()
    } yield {
      assert(entity.id.nonEmpty)
      assertEquals(entity.items, Map.empty[String, Int])
    }
  }

  test("create produces distinct ids across calls") {
    for {
      store <- CartStore.inMemory[IO]
      first <- store.create()
      second <- store.create()
    } yield assertNotEquals(first.id, second.id)
  }

  test("get returns the persisted cart") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      found <- store.get(created.id)
    } yield assertEquals(found, Some(created))
  }

  test("get returns None for an unknown id") {
    for {
      store <- CartStore.inMemory[IO]
      found <- store.get("unknown-id")
    } yield assertEquals(found, None)
  }

  test("addItem on a new sku inserts it at the given quantity") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      updated <- store.addItem(created.id, "sku-1", 3)
    } yield assertEquals(updated.map(_.items), Some(Map("sku-1" -> 3)))
  }

  test(
    "addItem on an existing sku increments rather than overwrites its quantity"
  ) {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      _ <- store.addItem(created.id, "sku-1", 3)
      updated <- store.addItem(created.id, "sku-1", 2)
    } yield assertEquals(updated.map(_.items), Some(Map("sku-1" -> 5)))
  }

  test("addItem bumps updatedAt") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      updated <- store.addItem(created.id, "sku-1", 1)
    } yield assert(
      updated.exists(!_.updatedAt.isBefore(created.updatedAt)),
      s"expected updatedAt not to move backwards, got: $updated"
    )
  }

  test("addItem returns None for an unknown cart id") {
    for {
      store <- CartStore.inMemory[IO]
      result <- store.addItem("unknown-id", "sku-1", 1)
    } yield assertEquals(result, None)
  }

  test("removeItem deletes a sku entirely, regardless of its quantity") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      _ <- store.addItem(created.id, "sku-1", 5)
      updated <- store.removeItem(created.id, "sku-1")
    } yield assertEquals(updated.map(_.items), Some(Map.empty[String, Int]))
  }

  test("removeItem on a sku not present in the cart is a no-op") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      _ <- store.addItem(created.id, "sku-1", 2)
      updated <- store.removeItem(created.id, "sku-2")
    } yield assertEquals(updated.map(_.items), Some(Map("sku-1" -> 2)))
  }

  test("removeItem returns None for an unknown cart id") {
    for {
      store <- CartStore.inMemory[IO]
      result <- store.removeItem("unknown-id", "sku-1")
    } yield assertEquals(result, None)
  }

  test("setQuantity with a positive value sets it exactly, not additively") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      _ <- store.addItem(created.id, "sku-1", 3)
      updated <- store.setQuantity(created.id, "sku-1", 10)
    } yield assertEquals(updated.map(_.items), Some(Map("sku-1" -> 10)))
  }

  test("setQuantity with 0 removes the sku") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      _ <- store.addItem(created.id, "sku-1", 3)
      updated <- store.setQuantity(created.id, "sku-1", 0)
    } yield assertEquals(updated.map(_.items), Some(Map.empty[String, Int]))
  }

  test("setQuantity with a negative value removes the sku") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      _ <- store.addItem(created.id, "sku-1", 3)
      updated <- store.setQuantity(created.id, "sku-1", -1)
    } yield assertEquals(updated.map(_.items), Some(Map.empty[String, Int]))
  }

  test("setQuantity returns None for an unknown cart id") {
    for {
      store <- CartStore.inMemory[IO]
      result <- store.setQuantity("unknown-id", "sku-1", 1)
    } yield assertEquals(result, None)
  }

  test("delete removes the cart and returns true, and get then returns None") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create()
      deleted <- store.delete(created.id)
      found <- store.get(created.id)
    } yield {
      assert(deleted)
      assertEquals(found, None)
    }
  }

  test("delete returns false for an unknown id") {
    for {
      store <- CartStore.inMemory[IO]
      deleted <- store.delete("unknown-id")
    } yield assert(!deleted)
  }

  test("ping returns true") {
    for {
      store <- CartStore.inMemory[IO]
      ready <- store.ping
    } yield assert(ready)
  }
}
