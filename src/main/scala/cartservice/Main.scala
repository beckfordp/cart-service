// SPDX-License-Identifier: MIT

package cartservice

import cats.MonadThrow
import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.typelevel.log4cats.StructuredLogger
import purerest.docs.Docs
import purerest.logging.Logging
import purerest.metrics.{Metrics, ServerMetrics}
import purerest.tracing.{ServerTracing, Tracing, TracingConfig}
import sttp.tapir.server.ServerEndpoint

object Main extends IOApp.Simple {

  /** The endpoints `Docs.routes` serves the spec for - the same list the
    * running service is built from, and what `CartDocsSuite` checks.
    */
  private[cartservice] def endpoints[F[_]: MonadThrow](
      store: CartStore[F],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]] =
    List(
      CartRoutes.serverEndpoint[F](store, logger),
      CartRoutes.getCartServerEndpoint[F](store, logger),
      CartRoutes.addItemServerEndpoint[F](store, logger),
      CartRoutes.removeItemServerEndpoint[F](store, logger),
      CartRoutes.deleteCartServerEndpoint[F](store, logger),
      HealthRoutes.healthServerEndpoint[F],
      HealthRoutes.readyServerEndpoint[F](store)
    )

  val run: IO[Unit] =
    for {
      config <- CartServiceConfig.load[IO]
      port <- IO.fromOption(Port.fromInt(config.port))(
        new IllegalArgumentException(
          s"Invalid cart-service port: ${config.port}"
        )
      )
      // PERF-5.3: OTLP/HTTP to Tempo when OTEL_EXPORTER_OTLP_ENDPOINT is set,
      // else the console; a malformed OTEL_TRACES_SAMPLER_ARG fails startup.
      tracingConfig <- IO.defer(
        IO.fromEither(
          TracingConfig
            .fromEnv(sys.env)
            .left
            .map(new IllegalArgumentException(_))
        )
      )
      _ <- Tracing.resource[IO](config.serviceName, tracingConfig).use {
        tracer =>
          Metrics
            .oteljava[IO](
              config.serviceName,
              config.metricsPort,
              Some(runtime.metrics)
            )
            .use { meter =>
              for {
                logger <- Logging.create[IO](tracer, config.serviceName)
                _ <- logger.info(
                  Map(
                    "port" -> config.port.toString,
                    "metrics_port" -> config.metricsPort.toString
                  )
                )("cart-service starting")
                _ <- CartStore
                  .redis[IO](config.redis, meter, logger)
                  .evalMap(CartJourneyMetrics.instrument[IO](_, meter))
                  .use { store =>
                    val docsRoutes = Docs.routes[IO](
                      "Cart Service",
                      "1.0",
                      endpoints[IO](store, logger)
                    )
                    val tracedRoutes =
                      ServerTracing.middleware(tracer)(docsRoutes)
                    val meteredRoutes =
                      ServerMetrics.middleware[IO](meter)(tracedRoutes)
                    val routes = Cors.middleware[IO](meteredRoutes)
                    EmberServerBuilder
                      .default[IO]
                      .withHost(host"0.0.0.0")
                      .withPort(port)
                      .withHttpApp(routes.orNotFound)
                      .build
                      .useForever
                  }
              } yield ()
            }
      }
    } yield ()
}
