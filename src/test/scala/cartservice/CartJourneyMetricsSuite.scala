package cartservice

import cats.effect.IO
import cartservice.CartFixtures.{qty, sku}
import cartservice.domain.Quantity
import munit.CatsEffectSuite
import purerest.metrics.Metrics

import scala.jdk.CollectionConverters._

class CartJourneyMetricsSuite extends CatsEffectSuite {

  test("counts every cart write by operation and outcome") {
    Metrics.test[IO]("cart-journey").use { tm =>
      for {
        underlying <- CartStore.inMemory[IO]
        store <- CartJourneyMetrics.instrument[IO](underlying, tm.meter)
        cart <- store.create()
        _ <- store.addItem(cart.id, sku("sku-1"), qty(1))
        _ <- store.addItem(cart.id, sku("sku-1"), qty(Quantity.Max))
        _ <- store.setQuantity(cart.id, sku("sku-1"), None)
        _ <- store.removeItem(cart.id, sku("sku-1"))
        found <- store.get(cart.id)
        _ <- store.delete(cart.id)
        _ <- store.delete(cart.id)
        ready <- store.ping
        metrics <- tm.collectMetrics
      } yield {
        assert(found.isDefined)
        assert(ready)
        val points = metrics
          .find(_.getName == "cart.operations")
          .get
          .getLongSumData
          .getPoints
          .asScala
          .map { p =>
            val a =
              p.getAttributes.asMap.asScala
                .map((k, v) => k.getKey -> v.toString)
            (a("operation"), a("outcome")) -> p.getValue
          }
          .toMap
        assertEquals(
          points,
          Map(
            ("create", "ok") -> 1L,
            ("add_item", "ok") -> 1L,
            ("add_item", "invalid_quantity") -> 1L,
            ("set_quantity", "ok") -> 1L,
            ("remove_item", "ok") -> 1L,
            ("delete", "ok") -> 1L,
            ("delete", "not_found") -> 1L
          )
        )
      }
    }
  }

  test("labels every error outcome") {
    assertEquals(
      List(
        domain.CartNotFound,
        domain.InvalidQuantity,
        domain.InvalidSku
      ).map(CartJourneyMetrics.outcome),
      List("not_found", "invalid_quantity", "invalid_sku")
    )
  }
}
