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

  /** Whether or not to perform the active request action. */
  sealed trait RequestFilterAction extends Product with Serializable

  object RequestFilterAction {
    /** Perform the active request action. */
    case object PerformAction extends RequestFilterAction
    /** Bypass the active request action. */
    case object ByPassAction extends RequestFilterAction
  }

  /** A `UnaryOperator` for updating the `AtomicReference` state. */
  private[this] def unaryOp[S](f: S => S): UnaryOperator[S] =
    new UnaryOperator[S] {
      final override def apply(s: S): S = f(s)
    }

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
  )(implicit F: Sync[F]
  ): HttpMiddleware[F] = {

    def use(
      service: Kleisli[OptionT[F, ?], Request[F], Response[F]],
      req: Request[F]
    ): Stream[F, Option[Response[F]]] =
      Stream.eval(
        action(req).flatMap {
          case Left(req) =>
            service.run(req).value
          case Right(resp) =>
            F.pure(Option(resp))
        }
      )

    (service: Kleisli[OptionT[F, ?], Request[F], Response[F]]) =>
      Kleisli[OptionT[F, ?], Request[F], Response[F]](
        (req: Request[F]) =>
          OptionT(
            Stream
              .bracket(onStart)(
                use = Function.const(use(service, req)),
                release = Function.const(onEnd)
              )
              .compile
              .toList
              .map((l: List[Option[Response[F]]]) => l.headOption.flatten)
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
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return the middleware.
    */
  def activeRequestCountMiddleware[F[_], N](
    startReport: N => F[Unit],
    endReport: N => F[Unit],
    action: (N, Request[F]) => F[Either[Request[F], Response[F]]]
  )(implicit F: Sync[F],
    N: Numeric[N]
  ): HttpMiddleware[F] = {
    val state: AtomicReference[N] = new AtomicReference(N.zero)
    val inspect: F[N]             = F.delay(state.get)
    val succ: F[Unit] = for {
      n <- F.delay(state.updateAndGet(unaryOp((n: N) => N.plus(n, N.one))))
      unit <- startReport(n)
    } yield unit

    val pred: F[Unit] = for {
      n <- F.delay(state.updateAndGet(unaryOp((n: N) => N.minus(n, N.one))))
      unit <- endReport(n)
    } yield unit

    val primitiveAction: Request[F] => F[Either[Request[F], Response[F]]] =
      ((r: Request[F]) => inspect.flatMap((n: N) => action(n, r)))

    this.primitive(succ, pred, primitiveAction)
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
    * @param requestAction a function yielding a [[RequestFilterAction]] which
    *                      determines if the given request should be processed
    *                      even if we are at the max concurrent request value.
    *
    * @param maxConcurrentRequests the maximum number of concurrent requests
    *        to allow.
    *
    * @tparam F a `Sync` type.
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return the middleware.
    */
  def rejectWithResponseOverMaxMiddleware_[F[_], N](
    startReport: N => F[Unit],
    endReport: N => F[Unit],
    onMax: F[Unit],
    response: Response[F],
    requestAction: Request[F] => RequestFilterAction
  )(
    maxConcurrentRequests: N
  )(implicit F: Sync[F],
    N: Numeric[N]
  ): HttpMiddleware[F] = {
    val resp: Either[Request[F], Response[F]] = Right(response)
    this.activeRequestCountMiddleware(
      startReport,
      endReport,
      (currentActiveRequests: N, req: Request[F]) =>
        if (requestAction(req) == RequestFilterAction.ByPassAction) {
          F.pure(Left(req))
        } else if (N.gt(currentActiveRequests, maxConcurrentRequests)) {
          onMax.map(Function.const(resp))
        } else {
          F.pure(Left(req))
        }
    )
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
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return the middleware.
    */
  def rejectWithResponseOverMaxMiddleware[F[_], N](
    startReport: N => F[Unit],
    endReport: N => F[Unit],
    onMax: F[Unit],
    response: Response[F]
  )(
    maxConcurrentRequests: N
  )(implicit F: Sync[F],
    N: Numeric[N]
  ): HttpMiddleware[F] =
    this.rejectWithResponseOverMaxMiddleware_[F, N](
      startReport,
      endReport,
      onMax,
      response,
      Function.const(RequestFilterAction.PerformAction)
    )(maxConcurrentRequests)

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
    * @param requestAction a function yielding a [[RequestFilterAction]] which
    *                      determines if the given request should be processed
    *                      even if we are at the max concurrent request value.
    *
    * @tparam F a `Sync` type.
    * @tparam N a `Numeric` state type, e.g. `Long`.
    *
    * @return the middleware.
    */
  def serviceUnavailableMiddleware_[F[_], N](
    startReport: N => F[Unit],
    endReport: N => F[Unit],
    onMax: F[Unit],
    maxConcurrentRequests: N,
    requestActionFilter: Request[F] => RequestFilterAction
  )(implicit F: Sync[F],
    N: Numeric[N]
  ): HttpMiddleware[F] =
    this.rejectWithResponseOverMaxMiddleware_[F, N](
      startReport,
      endReport,
      onMax,
      Response(status = Status.ServiceUnavailable),
      requestActionFilter
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
    * @return the middleware.
    */
  def serviceUnavailableMiddleware[F[_], N](
    maxConcurrentRequests: N
  )(implicit F: Sync[F],
    N: Numeric[N]
  ): HttpMiddleware[F] = {
    val const: N => F[Unit] = Function.const(F.pure(()))
    this.serviceUnavailableMiddleware_(
      const,
      const,
      F.pure(()),
      maxConcurrentRequests,
      Function.const(RequestFilterAction.PerformAction)
    )
  }
}
