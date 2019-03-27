package io.isomarcte.http4s.active.requests.core

import cats.data._
import cats.effect._
import cats.implicits._
import fs2.Stream
import java.util.concurrent.atomic._
import java.util.function.UnaryOperator
import org.http4s._
import org.http4s.server._

/** Http4s middleware which allows for introspection based on the number of active requests. */
object ActiveRequestMiddleware {

  /** A `UnaryOperator` for updating the `AtomicReference` state. */
  private[this] def unaryOp[S](
    f: S => S
  ): UnaryOperator[S] =
    new UnaryOperator[S] {
      override final def apply(s: S): S = f(s)
    }

  /** This function provides the fundamental primitive operations on which the
    * other functions are defined. It is quite a bit uglier than the higher
    * level middlewares. Unless you have very specific needs, you should avoid
    * using it directly.
    *
    * Fundamentally it takes an initial state, and two unary functions to
    * apply to that state at the start and end of a request. For example, if
    * you are merely interested in counting the number of active requests, you
    * might choose `Long` as your state and `+1` and `-1` as your unary
    * functions.
    *
    * @param  initial the initial state value.
    * @param  onStart a function to apply to the current state each time a new
    *                 `Request` is received.
    * @param  onEnd   a function to apply to the current state each time a
    *                 `Response` is returned.
    * @param action a function of the current state and the `Request` which
    *                yields either a `Request` or a `Response`. If a
    *                `Response` is yielded this has the effect of bypassing
    *                the underlying service.
    *
    * @tparam F a `Sync` type.
    * @tparam S the state.
    *
    * @return a pair of a `F[S]` which can be used to inspect the current
    *         state externally and the middleware.
    */
  def primitive[F[_], S](
    initial: S,
    onStart: S => S,
    onEnd: S => S,
    action: (S, Request[F]) => F[Either[Request[F], Response[F]]]
  )(
    implicit F: Sync[F]
  ): (F[S], HttpMiddleware[F]) = {
    val state: AtomicReference[S] = new AtomicReference(initial)
    val onStartOp: F[S] = F.delay(state.updateAndGet(unaryOp(onStart)))
    val onEndOp: F[Unit] = F.delay(state.updateAndGet(unaryOp(onEnd))).void

    def use(
      service: Kleisli[OptionT[F, ?], Request[F], Response[F]],
      req: Request[F]
    )(s: S): Stream[F, Option[Response[F]]] =
      Stream.eval(
        action(s, req).flatMap{
          case Left(req) =>
            service.run(req).value
          case Right(resp) =>
            F.pure(Option(resp))
        }
      )

    (F.delay(state.get),
      (service: Kleisli[OptionT[F, ?], Request[F], Response[F]]) =>
      Kleisli[OptionT[F, ?], Request[F], Response[F]]((req: Request[F]) =>
        OptionT(
          Stream.bracket(onStartOp)(
            use = use(service, req),
            release = Function.const(onEndOp)
          ).compile.toList.map((l: List[Option[Response[F]]]) => l.headOption.flatten))
      )
    )
  }

  /** Middleware which counts the active requests and allows for an action to be
    * taken based off the current number of active requests and the current
    * request.
    *
    * @param action a function of the number of active requests and the
    *                current `Request` which yields either a `Request` or a
    *                `Response`. If a `Response` is yielded this has the
    *                effect of bypassing the underlying service.
    *
    * @tparam F a `Sync` type.
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return a pair of a `F[S]` which can be used to inspect the current
    *         state externally and the middleware.
    */
  def activeRequestCountMiddleware[F[_], N](
    action: (N, Request[F]) => F[Either[Request[F], Response[F]]]
  )(
    implicit F: Sync[F],
             N: Numeric[N]
  ): (F[N], HttpMiddleware[F]) =
    this.primitive(N.zero, (n: N) => N.plus(n, N.one), (n: N) => N.minus(n, N.one), action)

  /** Middleware which bypasses the service if there are more than a certain
    * number of active requests.
    *
    * @param response the `Response` to yield when there are too many active
    *        requests.
    *
    * @param maxConcurrentRequests the maximum number of concurrent requests
    *        to allow.
    *
    * @tparam F a `Sync` type.
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return a pair of a `F[S]` which can be used to inspect the current
    *         state externally and the middleware.
    */
  def rejectWithResponseOverMaxMiddleware_[F[_], N](
    response: Response[F]
  )(
    maxConcurrentRequests: N
  )(
    implicit F: Sync[F],
             N: Numeric[N]
  ): (F[N], HttpMiddleware[F]) = {
    val resp: Either[Request[F], Response[F]] = Right(response)
    this.activeRequestCountMiddleware(
      (currentActiveRequests: N, req: Request[F]) =>
      if (N.gt(currentActiveRequests, maxConcurrentRequests)) {
        F.pure(resp)
      } else {
        F.pure(Left(req))
      }
    )
  }

  /** Middleware which bypasses the service if there are more than a certain
    * number of active requests.
    *
    * @param response the `Response` to yield when there are too many active
    *        requests.
    *
    * @param maxConcurrentRequests the maximum number of concurrent requests
    *        to allow.
    *
    * @tparam F a `Sync` type.
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return the middleware.
    */
  def rejectWithResponseOverMaxMiddleware[F[_], N](
    response: Response[F]
  )(
    maxConcurrentRequests: N
  )(
    implicit F: Sync[F],
             N: Numeric[N]
  ): HttpMiddleware[F] = this.rejectWithResponseOverMaxMiddleware_(response)(maxConcurrentRequests)._2


  /** Middleware which returns a 503 (ServiceUnavailable) response after it is
    * processing more than a given number of requests.
    *
    * @param maxConcurrentRequests the maximum number of concurrent requests
    *        to allow.
    *
    * @tparam F a `Sync` type.
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return a pair of a `F[S]` which can be used to inspect the current
    *         state externally and the middleware.
    */
  def serviceUnavailableMiddleware_[F[_], N](maxConcurrentRequests: N)(implicit F: Sync[F], N: Numeric[N]): (F[N], HttpMiddleware[F]) =
    this.rejectWithResponseOverMaxMiddleware_[F, N](
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
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return the middleware
    */
  def serviceUnavailableMiddleware[F[_], N](
    maxConcurrentRequests: N
  )(
    implicit F: Sync[F],
    N: Numeric[N]
  ): HttpMiddleware[F] =
    this.serviceUnavailableMiddleware_(maxConcurrentRequests)._2
}
