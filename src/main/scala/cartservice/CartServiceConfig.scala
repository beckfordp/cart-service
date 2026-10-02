package cartservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}

final case class CartServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String
) derives ConfigReader

object CartServiceConfig {
  def load[F[_]: Sync]: F[CartServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[CartServiceConfig])
}
