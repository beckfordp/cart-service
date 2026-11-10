// SPDX-License-Identifier: MIT

package cartservice

import cats.Monad
import org.http4s.{HttpRoutes, Method}
import org.http4s.server.middleware.CORS
import org.typelevel.ci._

/** CORS policy: allow all origins (gshop's origin varies per environment),
  * restricted to the methods/headers the cart routes actually use (GET, POST,
  * DELETE; `Content-Type`), credentials disallowed (required anyway alongside
  * an allow-all-origins policy — browsers reject credentialed requests against
  * a wildcard origin).
  */
object Cors {

  def middleware[F[_]: Monad](routes: HttpRoutes[F]): HttpRoutes[F] =
    CORS.policy.withAllowOriginAll
      .withAllowMethodsIn(
        Set(Method.GET, Method.POST, Method.DELETE)
      )
      .withAllowHeadersIn(Set(ci"Content-Type"))
      .withAllowCredentials(false)
      .apply(routes)
}
