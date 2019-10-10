package io.isomarcte.http4s.active.requests.core

import cats.effect._
import org.scalatest._

abstract class BaseTest extends FlatSpec with Matchers {
  def io[A](io: IO[A]): A = io.unsafeRunSync
}
