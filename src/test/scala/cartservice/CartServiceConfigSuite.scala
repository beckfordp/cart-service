package cartservice

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource

class CartServiceConfigSuite extends CatsEffectSuite {

  private val validHocon =
    """
      |port = 8080
      |metrics-port = 9090
      |service-name = "cart-service"
      |redis {
      |  uri = "redis://localhost:6379"
      |  ttl-seconds = 86400
      |}
      |""".stripMargin

  test("loads a fully-specified config") {
    val result =
      ConfigSource.string(validHocon).load[CartServiceConfig]
    assertEquals(
      result,
      Right(
        CartServiceConfig(
          port = 8080,
          metricsPort = 9090,
          serviceName = "cart-service",
          redis = RedisConfig(
            uri = "redis://localhost:6379",
            ttlSeconds = 86400
          )
        )
      )
    )
  }

  test("fails to load when a required field is missing") {
    val missingRedis =
      """
        |port = 8080
        |metrics-port = 9090
        |service-name = "cart-service"
        |""".stripMargin

    assert(
      ConfigSource
        .string(missingRedis)
        .load[CartServiceConfig]
        .isLeft
    )
  }

  test("load[F] reads the shipped application.conf defaults") {
    CartServiceConfig.load[IO].map { config =>
      assertEquals(config.port, 8080)
      assertEquals(config.metricsPort, 9090)
      assertEquals(config.serviceName, "cart-service")
      assertEquals(
        config.redis,
        RedisConfig(uri = "redis://localhost:6379", ttlSeconds = 86400)
      )
    }
  }
}
