package cartservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}

// Not yet wired into CartServiceConfig below - this track's Phase 3 adds the
// `redis: RedisConfig` field once Main.scala is wired to CartStore.redis.
final case class RedisConfig(
    uri: String,
    ttlSeconds: Int
) derives ConfigReader

final case class CartServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String
) derives ConfigReader

object CartServiceConfig {
  def load[F[_]: Sync]: F[CartServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[CartServiceConfig])
}
