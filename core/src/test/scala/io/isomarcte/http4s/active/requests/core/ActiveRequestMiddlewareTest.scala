package io.isomarcte.http4s.active.requests.core.unit

import cats.data._
import cats.effect._
import cats.implicits._
import io.isomarcte.http4s.active.requests.core._
import java.util.concurrent._
import java.util.concurrent.atomic._
import org.http4s._
import org.http4s.server._
import scala.concurrent._

final class ActiveRequestMiddlewareTest extends BaseTest {

  "serviceUnavailableMiddleware" should "reject requests if there are too many concurrently running" in io {
    val ec: ExecutionContext           = BaseTest.cachedEC
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    val limit: Int                     = 1
    val latch: CountDownLatch          = new CountDownLatch(2)
    val semaphore: Semaphore           = new Semaphore(limit)
    val onMaxCount: AtomicInteger      = new AtomicInteger(0)
    val onStartReportValue: AtomicLong = new AtomicLong(0L)
    val onEndReportValue: AtomicLong   = new AtomicLong(0L)
    val startPoll: IO[Long]            = IO(onStartReportValue.get)
    val endPoll: IO[Long]              = IO(onEndReportValue.get)
    val request: Request[IO]           = Request[IO]()
    val f: IO[Unit]                    = IO(latch.countDown()) *> IO(semaphore.acquire())
    val onMax: IO[Unit]                = IO(onMaxCount.incrementAndGet()).void
    val middleware: HttpMiddleware[IO] =
      ActiveRequestMiddleware.serviceUnavailableMiddleware_[IO, Long](
        (l: Long) => IO(onStartReportValue.set(l)),
        (l: Long) => IO(onEndReportValue.set(l)),
        onMax,
        limit.toLong
      )
    val service: HttpRoutes[IO] =
      middleware(ActiveRequestMiddlewareTest.effectService[IO](f))

    for {
      // T0
      resp0 <- service.run(request).value // Should be Ok
      start0 <- startPoll
      end0 <- endPoll

      // T1
      // Out of permits so this will block. We will join the Fiber later
      fiber1 <- (IO.shift(ec) *> service.run(request).value).start

      // Wait for the latch to reach zero before we poll. This should indicate
      // that we have already incremented the counter.
      _ <- IO(latch.await())
      start1 <- startPoll
      end1 <- endPoll

      // T2
      // This should _not_ block, because there are too many active request. It should return a 503 immediately.
      resp2 <- service.run(request).value // Should be ServiceUnavailable
      start2 <- startPoll
      end2 <- endPoll

      // T3
      // Release a permit to allow `fiber1` to complete.
      _ <- IO(semaphore.release())
      resp1 <- fiber1.join // Should be Ok
      start3 <- startPoll
      end3 <- endPoll
      onMaxValue <- IO(onMaxCount.get)
    } yield {
      // T0
      resp0.get.status shouldBe Status.Ok
      start0 shouldBe 1L
      end0 shouldBe 0L

      // T1
      start1 shouldBe 1L
      end1 shouldBe 0L
      resp1.get.status shouldBe Status.Ok

      // T2
      resp2.get.status shouldBe Status.ServiceUnavailable
      start2 shouldBe 2L
      end2 shouldBe 1L

      // T3
      start3 shouldBe 2L
      end3 shouldBe 0L

      // Check number of times the onMax event fired
      onMaxValue shouldBe 1
    }
  }
}

object ActiveRequestMiddlewareTest {

  def effectService[F[_]](f: F[Unit])(implicit F: Sync[F]): HttpRoutes[F] = {
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
