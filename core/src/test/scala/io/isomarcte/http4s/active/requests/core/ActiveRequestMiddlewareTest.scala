package io.isomarcte.http4s.active.requests.core.unit

import cats.data._
import cats.effect._
import cats.implicits._
import io.isomarcte.http4s.active.requests.core._
import java.util.concurrent._
import org.http4s._
import org.http4s.server._
import scala.concurrent._

final class ActiveRequestMiddlewareTest extends BaseTest {

  "serviceUnavailableMiddleware" should "reject requests if there are too many concurrently running" in io {
    val ec: ExecutionContext  = BaseTest.cachedEC
    val limit: Int            = 1
    val latch: CountDownLatch = new CountDownLatch(2)
    val semaphore: Semaphore  = new Semaphore(limit)
    val request: Request[IO]  = Request[IO]()
    val f: IO[Unit]           = IO(latch.countDown()) *> IO(semaphore.acquire())
    val (activeRequests, middleware): (IO[Long], HttpMiddleware[IO]) =
      ActiveRequestMiddleware.serviceUnavailableMiddleware_[IO, Long](
        limit.toLong
      )
    val service: HttpService[IO] =
      middleware(ActiveRequestMiddlewareTest.effectService[IO](f))

    for {
      // T0
      resp0 <- service.run(request).value // Should be Ok
      count0 <- activeRequests // Should be 0

      // T1
      // Out of permits so this will block. We will join the Fiber later
      fiber1 <- (IO.shift(ec) *> service.run(request).value).start

      // Wait for the inner effect to run before we poll the activeRequest state.
      _ <- IO(latch.await())
      count1 <- activeRequests // Should be 1

      // T2
      // This should _not_ block, because there are too many active request. It should return a 503 immediately.
      resp2 <- service.run(request).value // Should be ServiceUnavailable
      count2 <- activeRequests // Should be 1

      // T3
      // Release a permit to allow `fiber1` to complete.
      _ <- IO(semaphore.release())
      resp1 <- fiber1.join // Should be Ok
      count3 <- activeRequests // Should be 0
    } yield {
      // T0
      resp0.get.status shouldBe Status.Ok
      count0 shouldBe 0L

      // T1
      count1 shouldBe 1L
      resp1.get.status shouldBe Status.Ok

      // T2
      resp2.get.status shouldBe Status.ServiceUnavailable
      count2 shouldBe 1L

      // T3
      count3 shouldBe 0L
    }
  }
}

object ActiveRequestMiddlewareTest {

  def effectService[F[_]](f: F[Unit])(implicit F: Sync[F]): HttpService[F] = {
    val resp: Option[Response[F]] = Some(Response(status = Status.Ok))
    Kleisli(
      Function.const(
        OptionT(
          f.map(
            Function.const(
              resp
            )
          )
        )
      )
    )
  }
}
