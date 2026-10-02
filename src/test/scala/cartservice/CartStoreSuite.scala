package cartservice

import cats.effect.IO
import munit.CatsEffectSuite

class CartStoreSuite extends CatsEffectSuite {

  test("create returns a persisted entity with a generated id") {
    for {
      store <- CartStore.inMemory[IO]
      entity <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
    } yield assert(entity.id.nonEmpty)
  }

  test("get returns the persisted entity") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      found <- store.get(created.id)
    } yield assertEquals(found, Some(created))
  }

  test("get returns None for an unknown id") {
    for {
      store <- CartStore.inMemory[IO]
      found <- store.get("unknown-id")
    } yield assertEquals(found, None)
  }

  test("create produces distinct ids across calls") {
    for {
      store <- CartStore.inMemory[IO]
      first <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      second <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
    } yield assertNotEquals(first.id, second.id)
  }

  test("update returns the updated entity with updatedAt not moving backwards") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
      updated <- store.update(created.id/* codegen:fields:TEST_UPDATE_ARGS */)
    } yield {
      assertEquals(updated.map(_.id), Some(created.id))
      assert(
        updated.exists(!_.updatedAt.isBefore(created.updatedAt)),
        s"expected updatedAt not to move backwards, got: $updated"
      )
    }
  }

  test("update returns None for an unknown id") {
    for {
      store <- CartStore.inMemory[IO]
      result <- store.update("unknown-id"/* codegen:fields:TEST_UPDATE_ARGS */)
    } yield assertEquals(result, None)
  }

  test("delete removes the entity and returns true, and get then returns None") {
    for {
      store <- CartStore.inMemory[IO]
      created <- store.create(/* codegen:fields:TEST_CREATE_ARGS */)
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
}
