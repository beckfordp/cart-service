package cartservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}

final case class RedisConfig(
    uri: String,
    ttlSeconds: Int
) derives ConfigReader

final case class CartServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    redis: RedisConfig
) derives ConfigReader

object CartServiceConfig {
  def load[F[_]: Sync]: F[CartServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[CartServiceConfig])
}
