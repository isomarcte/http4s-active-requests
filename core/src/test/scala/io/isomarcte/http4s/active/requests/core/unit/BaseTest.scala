package io.isomarcte.http4s.active.requests.core.unit

import cats.effect._
import java.util.concurrent.Executors
import org.scalatest._
import scala.concurrent._

abstract class BaseTest extends FlatSpec with Matchers {
  def io[A](io: IO[A]): A = io.unsafeRunSync
}

object BaseTest {
  val cachedEC: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newCachedThreadPool()
  )
}
