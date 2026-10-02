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
      |postgres {
      |  host = "localhost"
      |  port = 5432
      |  database = "cart"
      |  user = "cart"
      |  password = "cart"
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
          postgres = PostgresConfig(
            host = "localhost",
            port = 5432,
            database = "cart",
            user = "cart",
            password = "cart"
          )
        )
      )
    )
  }

  test("fails to load when a required field is missing") {
    val missingPassword =
      """
        |port = 8080
        |metrics-port = 9090
        |postgres {
        |  host = "localhost"
        |  port = 5432
        |  database = "cart"
        |  user = "cart"
        |}
        |""".stripMargin

    assert(
      ConfigSource
        .string(missingPassword)
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
        config.postgres,
        PostgresConfig(
          "localhost",
          5432,
          "cart",
          "cart",
          "cart"
        )
      )
    }
  }
}
