package io.isomarcte.http4s.active.requests.core

import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import org.http4s._
import org.http4s.server._

/** Http4s middleware which allows for introspection based on the number of active requests. */
object ActiveRequestMiddleware {

  /** This function provides the fundamental primitive operations on which the
    * other functions are defined. It is quite a bit uglier than the higher
    * level middlewares. Unless you have very specific needs, you should avoid
    * using it directly.
    *
    * Fundamentally takes two effects, one to run when a request is received,
    * and one to run when a response is emitted. It also takes an action,
    * which is a function of the current request, which emits an effect of an
    * `Either[Request[F], Response[F]]`. If a `Right[Response[F]]` is emitted,
    * this will bypass calling the underlying service and immediately return
    * the generated `Response[F]` value.
    *
    * @param initial the initial state value.
    * @param onStart an effect to run after a request is received.
    * @param onEnd an effect to run after a response is emitted.
    * @param action a function of the current `Request` which yields either a
    *                `Request` or a `Response`. If a `Response` is yielded
    *                this has the effect of bypassing the underlying service.
    *
    * @tparam F a `Sync` type.
    *
    * @return a pair of a `F[S]` which can be used to inspect the current
    *         state externally and the middleware.
    */
  def primitive[F[_]](
    onStart: F[Unit],
    onEnd: F[Unit],
    action: Request[F] => F[Either[Request[F], Response[F]]]
  )(implicit F: Bracket[F, Throwable]
  ): HttpMiddleware[F] = {

    (service: Kleisli[OptionT[F, ?], Request[F], Response[F]]) =>
      Kleisli[OptionT[F, ?], Request[F], Response[F]](
        (req: Request[F]) =>
          OptionT(
            F.bracket(onStart)(
              Function.const(
                action(req).flatMap {
                  case Left(req) =>
                    service
                      .map(
                        (resp: Response[F]) =>
                          resp.copy(body = resp.body.onFinalize(onEnd))
                      )
                      .run(req)
                      .value
                  case Right(resp) => F.pure(resp.some)
                }
              )
            )(Function.const(onEnd))
          )
      )
  }

  /** Middleware which counts the active requests and allows for an action to be
    * taken based off the current number of active requests and the current
    * request.
    *
    * @param startReport report the request count every time it is
    *                    incremented.
    * @param endReport report the request count every time it is decremented.
    * @param action a function of the number of active requests and the
    *                current `Request` which yields either a `Request` or a
    *                `Response`. If a `Response` is yielded this has the
    *                effect of bypassing the underlying service.
    *
    * @tparam F a `Sync` type.
    * @tparam G a `Sync` type.
    *
    * @return the middleware.
    */
  def activeRequestCountMiddleware[F[_], G[_]](
    startReport: Long => F[Unit],
    endReport: Long => F[Unit],
    action: (Long, Request[F]) => F[Either[Request[F], Response[F]]]
  )(implicit F: Sync[F],
    G: Sync[G]
  ): G[HttpMiddleware[F]] =
    Ref.in[G, F, Long](0L).map { (ref: Ref[F, Long]) =>
      def modifyAndReturn(f: Long => Long)(l: Long): (Long, Long) = {
        val result: Long = f(l)
        (result, result)
      }
      val succ: F[Unit] =
        ref.modify(modifyAndReturn(_ + 1L)).flatMap(startReport)
      val pred: F[Unit] =
        ref.modify(modifyAndReturn(_ - 1L)).flatMap(endReport)
      val primitiveAction: Request[F] => F[Either[Request[F], Response[F]]] =
        ((r: Request[F]) => ref.get.flatMap((n: Long) => action(n, r)))
      this.primitive[F](succ, pred, primitiveAction)
    }

  /** Middleware which bypasses the service if there are more than a certain
    * number of active requests. When the `onMax` effect is invoked, this
    * ''always'' indicates that the given `Response[F]` value is yielded,
    * bypassing the underlying service.
    *
    * @param startReport report the request count every time it is
    *                    incremented.
    * @param endReport report the request count every time it is decremented.
    * @param onMax an effect to invoke if the permitted maximum number of
    *              concurrent requests is exceeded. One might use this for to
    *              log the event for example.
    * @param response the `Response` to yield when there are too many active
    *        requests.
    *
    * @param maxConcurrentRequests the maximum number of concurrent requests
    *        to allow.
    *
    * @tparam F a `Sync` type.
    *
    * @return the middleware.
    */
  def rejectWithResponseOverMaxMiddleware[F[_], G[_]](
    startReport: Long => F[Unit],
    endReport: Long => F[Unit],
    onMax: F[Unit],
    response: Response[F]
  )(
    maxConcurrentRequests: Long
  )(implicit F: Sync[F],
    G: Sync[G]
  ): G[HttpMiddleware[F]] = {
    val resp: Either[Request[F], Response[F]] = Right(response)
    this.activeRequestCountMiddleware[F, G](
      startReport,
      endReport, { (currentActiveRequests: Long, req: Request[F]) =>
        if (currentActiveRequests > maxConcurrentRequests) {
          onMax.map(Function.const(resp))
        } else {
          F.pure(Left(req))
        }
      }
    )
  }

  /** Middleware which returns a 503 (ServiceUnavailable) response after it is
    * processing more than a given number of requests.
    *
    * @param startReport report the request count every time it is
    *                    incremented.
    * @param endReport report the request count every time it is decremented.
    * @param onMax an effect to invoke if the permitted maximum number of
    *              concurrent requests is exceeded. One might use this for to
    *              log the event for example.
    * @param maxConcurrentRequests the maximum number of concurrent requests
    *        to allow.
    *
    * @tparam F a `Sync` type.
    *
    * @return the middleware.
    */
  def serviceUnavailableMiddleware_[F[_]](
    startReport: Long => F[Unit],
    endReport: Long => F[Unit],
    onMax: F[Unit],
    maxConcurrentRequests: Long
  )(implicit F: Sync[F]
  ): F[HttpMiddleware[F]] =
    this.rejectWithResponseOverMaxMiddleware[F, F](
      startReport,
      endReport,
      onMax,
      Response(status = Status.ServiceUnavailable)
    )(
      maxConcurrentRequests
    )

  /** Middleware which returns a 503 (ServiceUnavailable) response after it is
    * processing more than a given number of requests.
    *
    * @param maxConcurrentRequests the maximum number of concurrent requests
    *        to allow.
    *
    * @tparam F a `Sync` type.
    *
    * @return the middleware.
    */
  def serviceUnavailableMiddleware[F[_]](
    maxConcurrentRequests: Long
  )(implicit F: Sync[F]
  ): F[HttpMiddleware[F]] = {
    val const: Long => F[Unit] = Function.const(F.pure(()))
    this.serviceUnavailableMiddleware_(
      const,
      const,
      F.pure(()),
      maxConcurrentRequests
    )
  }
}
