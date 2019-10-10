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
import org.http4s._
import org.http4s.client._
import org.http4s.client.dsl.io._
import org.http4s.dsl._
import org.http4s.server.blaze._
import scala.concurrent.ExecutionContext

final class ActiveRequestMiddlewareItTest extends BaseTest {
  import ActiveRequestMiddlewareItTest._

  "Running requests through a live http4s blaze server" should "correctly increment and decrement the active request counter" in io {
    testServer[IO](ioTimer)(
      Kleisli{(testData: TestData[IO]) =>
        val client: Client[IO] = testData.testClient
        val postBody: String = "K&R"
        for {
          responseBody <- client.expect[String](Method.POST(postBody, testData.baseUri))
          unit <- IO(responseBody shouldBe postBody).void
        } yield unit
      }
    )
  }
}

object ActiveRequestMiddlewareItTest {

  final case class TestData[F[_]](
    baseUri: Uri,
    testClient: Client[F]
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

  private[this] def routes[F[_]: Sync]: Http[F, F] = {
    val dsl: Http4sDsl[F] = Http4sDsl[F]
    import dsl._
    Http[F, F]{
      case req @ POST -> Root =>
        Ok().map(_.withEntity(req.body))
    }
  }

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
    timer: Timer[F]
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
      for {
        _ <- Stream.eval(F.delay(println("Begin tests...")))
        _ <- Stream.eval(tests.run(testData))
        _ <- Stream.eval(F.delay(println("End tests...")))
      } yield ()

    Resource.make(
      SignallingRef[F, Boolean](false)
    )(_.set(true) *> F.delay(println("Server Shutdown Signaled"))).use((signallingRef: SignallingRef[F, Boolean]) =>
      Stream.eval(for {
        p <- port
        uri <- F.catchNonFatal(Uri.unsafeFromString(s"http://${this.testAddress.getHostName}:${p}/"))
        ref <- Ref.of(ExitCode.Success)
      } yield {
        this.blaze(p, timer).withHttpApp(routes).serveWhile(
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
          testStream(TestData(uri, client)))
        )
      }).flatten.compile.drain *>
        F.delay(println("Server Shutdown Completed"))
      ) *> F.delay(println("Resource Complete"))
  }
}
