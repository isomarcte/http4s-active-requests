package io.isomarcte.http4s.active.requests.core

import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import fs2._
import fs2.concurrent._
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.http4s._
import org.http4s.client._
import org.http4s.client.dsl.io._
import org.http4s.dsl._
import org.http4s.implicits._
import org.http4s.server._
import org.http4s.server.blaze._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final class ActiveRequestMiddlewareItTest extends BaseTest {
  import ActiveRequestMiddlewareItTest._

  "Running requests through a live http4s blaze server" should "correctly increment and decrement the active request counter" in io {
    val emptyRef: IO[Ref[IO, Long]] = Ref.of(0L)
    (emptyRef, emptyRef, emptyRef).mapN{(startRef: Ref[IO, Long], endRef: Ref[IO, Long], onMaxCountRef: Ref[IO, Long]) =>
      val onMax: IO[Unit] = onMaxCountRef.update(_ + 1L)
      ActiveRequestMiddleware.serviceUnavailableMiddleware_(
        startRef.set _,
        endRef.set _,
        onMax,
        1
      ).flatMap((middleware: HttpMiddleware[IO]) =>
        // testServer[IO](ioTimer, middleware, 1L)(Kleisli(Function.const(IO.unit))
        testServer[IO](ioTimer, middleware, 1L)(
          Kleisli{(testData: TestData[IO]) =>
            val client: Client[IO] = testData.testClient
            val postBody: String = "K&R"
            for {
              _ <- IO(println("client acquire")) *> testData.seeSawSemaphore.acquire
              responseBodyFiber <- client.expect[String](Method.POST(postBody, testData.baseUri)).start
              startT0 <- startRef.get
              _ <- testData.seeSawSemaphore.release
              responseBody <- responseBodyFiber.join
              _ <- IO(responseBody shouldBe postBody).void
              _ <- IO(startT0 shouldBe 1L)
            } yield ()
          }
        )
      )
    }.flatten
  }

  it should "Derp" in io {
    for {
      s <- SeeSawSemaphore[IO](1L)
      full = s.startsFullSeeSaw
      empty = s.startsEmptySeeSaw
      fsize0 <- full.value.available
      esize0 <- empty.value.available
      fcount0 <- full.value.count
      ecount0 <- empty.value.count
      _ <- full.value.acquire
      fsize1 <- full.value.available
      esize1 <- empty.value.available
    } yield {
      fsize0 shouldBe 1L
      esize0 shouldBe 0L
      fcount0 shouldBe 1L
      ecount0 shouldBe 0L
      fsize1 shouldBe 0L
      esize1 shouldBe 1L
    }
  }
}

object ActiveRequestMiddlewareItTest {

  final case class TestData[F[_]](
    baseUri: Uri,
    testClient: Client[F],
    seeSawSemaphore: SeeSawSemaphore[F]
  )

  implicit val ioTimer: Timer[IO] =
    IO.timer(ExecutionContext.global)

  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  private[this] val testAddress: InetAddress =
    InetAddress.getLoopbackAddress()

  private[this] lazy val blockingEC: ExecutionContext =
    ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool()
    )

  private[this] def testClient[F[_]: Async : ContextShift]: Stream[F, Client[F]] =
    JavaNetClientBuilder.apply[F](this.blockingEC).stream

  private[this] def startingPort[F[_]](random: Random)(implicit F: Sync[F]): F[Int] =
    F.delay(random.nextInt % 65536).flatMap((i: Int) =>
      if (i <= 2000) {
        startingPort(random)
      } else {
        F.pure(i)
      }
    )

  private[this] def freePort[F[_]](startingPort: Int, attempts: Int)(implicit F: Sync[F]): F[Int] =
    if (startingPort < 0 || startingPort > 65535) {
      F.raiseError(new RuntimeException(s"$startingPort is not a valid port. 0 <= p <= 65535"))
    } else {
      F.bracket(
        F.delay(new ServerSocket(startingPort))
      )(
        Function.const(F.pure(startingPort))
      )((s: ServerSocket) =>
        F.delay(s.close)
      ).handleErrorWith{(t: Throwable) =>
        if (attempts < 1 || startingPort === 65535) {
          F.raiseError(t)
        } else {
          freePort(startingPort + 1, attempts - 1)
        }
      }
    }

  private[this] def routes[F[_]: Sync]: HttpRoutes[F] = {
    val dsl: Http4sDsl[F] = Http4sDsl[F]
    import dsl._
    Http[F, F]{
      case req @ POST -> Root =>
        Ok().map(_.withEntity(req.body))
    }.mapF(OptionT.liftF(_))
  }

  private[this] def routesBracket[F[_]](
    beforeResponseEmit: F[Unit],
    afterResponseEmit: F[Unit]
  )(implicit F: Sync[F]): HttpMiddleware[F] = (service: HttpRoutes[F]) =>
  Kleisli((request: Request[F]) =>
    service.flatMapF((response: Response[F]) =>
      OptionT(
        beforeResponseEmit *> F.pure(response.copy(body = response.body.onFinalize(afterResponseEmit)).some))
    ).run(request)
  )

  private[this] def blaze[F[_]: ConcurrentEffect](
    port: Int,
    timer: Timer[F]
  ): BlazeServerBuilder[F] = {
    implicit val t: Timer[F] = timer
    BlazeServerBuilder[F].bindSocketAddress(
      InetSocketAddress.createUnresolved(this.testAddress.getHostAddress(), port)
    )
  }

  def testServer[F[_]](
    timer: Timer[F],
    middleware: HttpMiddleware[F],
    permits: Long
  )(
    tests: Kleisli[F, TestData[F], Unit]
  )(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): F[Unit] = {

    val port: F[Int] = for {
      random <- F.delay(new Random(System.currentTimeMillis()))
      sp <- this.startingPort(random)
      fp <- this.freePort(sp, 10)
      _ <- F.delay(println(s"Port Selected For Test Server: $fp"))
    } yield fp

    def testStream(testData: TestData[F]): Stream[F, Unit] =
      (for {
        _ <- Stream.eval(F.delay(println("Begin tests...")))
        _ <- Stream.eval(tests.run(testData))
        _ <- Stream.eval(F.delay(println("End tests...")))
      } yield ()).delayBy(FiniteDuration(50, TimeUnit.MILLISECONDS))(timer)

    Resource.make(
      SignallingRef[F, Boolean](false)
    )(_.set(true) *> F.delay(println("Server Shutdown Signaled"))).use((signallingRef: SignallingRef[F, Boolean]) =>
      Stream.eval(for {
        p <- port
        seeSawSemaphores <- SeeSawSemaphore(permits)
        uri <- F.catchNonFatal(Uri.unsafeFromString(s"http://${this.testAddress.getHostName}:${p}/"))
        ref <- Ref.of(ExitCode.Success)
      } yield {
        val wrappedRoutes: HttpRoutes[F] =
          middleware(
            this.routesBracket[F](
              F.delay(println("server acquire")) *> seeSawSemaphores.startsEmptySeeSaw.value.acquire,
              F.delay(println("server release")) *> seeSawSemaphores.startsEmptySeeSaw.value.release
            )(
              F
            )(
              this.routes[F]
            )
          )
        this.blaze(p, timer).withHttpApp(wrappedRoutes.orNotFound).serveWhile(
          signallingRef,
          ref
        ).evalMap{
          case ExitCode.Success =>
            F.delay(println("Server Shutdown Success"))
          case otherwise =>
            val errorString: String = s"Invalid ExitCode: $otherwise"
            F.delay(println(errorString)) *>
            F.raiseError[Unit](new AssertionError(errorString))
        }.onFinalize(F.delay(println("Server Shutdown Finalized"))
        ).mergeHaltR(this.testClient[F].flatMap((client: Client[F]) =>
          testStream(TestData(uri, client, seeSawSemaphores.startsFullSeeSaw.value)))
        )
      }).flatten.compile.drain *>
        F.delay(println("Server Shutdown Completed"))
      ) *> F.delay(println("Resource Complete"))
  }
}
