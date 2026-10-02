package cartservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}

final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String
) derives ConfigReader

final case class CartServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    postgres: PostgresConfig
) derives ConfigReader

object CartServiceConfig {
  def load[F[_]: Sync]: F[CartServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[CartServiceConfig])
}
