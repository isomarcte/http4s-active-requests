package io.isomarcte.http4s.active.requests.core

// import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._

sealed trait SeeSawSemaphore[F[_]] extends Semaphore[F] {
  def mateCount: F[Long]
  def mateAvailable: F[Long]
}

object SeeSawSemaphore {
  private[this] final case class Proxy[F[_]](value: Semaphore[F]) extends AnyVal
  private[this] final case class Mate[F[_]](value: Semaphore[F]) extends AnyVal

  private[this] final case class StartsFull[F[_]](value: Semaphore[F]) extends AnyVal
  private[this] object StartsFull {
    def apply[F[_]: Concurrent](permits: Long): F[StartsFull[F]] =
      Semaphore[F](permits).map((s: Semaphore[F]) => StartsFull(s))
  }
  private[this] sealed trait StartsEmpty[F[_]] extends Product with Serializable {
    def value: Semaphore[F]
    override final lazy val toString: String = s"StartsEmpty($value)"
  }

  private[this] object StartsEmpty {
    private[this] final case class StartsEmptyImpl[F[_]](
      override final val value: Semaphore[F]
    ) extends StartsEmpty[F]

    def empty[F[_]: Concurrent](permits: Long): F[StartsEmpty[F]] =
      for {
        s <- Semaphore(permits)
        _ <- s.acquireN(permits)
      } yield StartsEmptyImpl(s)
  }

  private[this] final case class SeeSawSemaphoreImpl[F[_]](
    proxy: Proxy[F],
    mate: Mate[F]
  )(implicit F: Sync[F]) extends SeeSawSemaphore[F] {

    override final def acquireN(n: Long): F[Unit] =
      this.proxy.value.acquireN(n) *> F.delay(println("middle")) *> this.mate.value.releaseN(n)
    override final val available: F[Long] =
      this.proxy.value.available
    override final val count: F[Long] =
      this.proxy.value.count
    override final def releaseN(n: Long): F[Unit] =
      this.proxy.value.releaseN(n) *> this.mate.value.acquireN(n)
    override final def tryAcquireN(n: Long): F[Boolean] =
      this.proxy.value.tryAcquireN(n).flatTap{
        case true =>
          this.mate.value.releaseN(n)
        case _ =>
          F.unit
      }
    override final def withPermit[A](t: F[A]): F[A] =
      this.proxy.value.withPermit(
        this.mate.value.release *> t
      ).flatTap(
        Function.const(this.mate.value.acquire)
      )

    override final val mateCount: F[Long] =
      this.mate.value.count

    override final val mateAvailable: F[Long] =
      this.mate.value.available

    override final lazy val toString: String =
      s"SeeSawSemaphore(${proxy.value})"
  }

  final case class StartsFullSeeSaw[F[_]](value: SeeSawSemaphore[F]) extends AnyVal
  final case class StartsEmptySeeSaw[F[_]](value: SeeSawSemaphore[F]) extends AnyVal

  sealed trait SeeSawSemaphores[F[_]] {
    def startsFullSeeSaw: StartsFullSeeSaw[F]
    def startsEmptySeeSaw: StartsEmptySeeSaw[F]
  }

  def apply[F[_]: Concurrent](permits: Long): F[SeeSawSemaphores[F]] =
    for {
      fullSemaphore <- StartsFull[F](permits)
      emptySemaphore <- StartsEmpty.empty(permits)
    } yield new SeeSawSemaphores[F] {
      override final val startsFullSeeSaw: StartsFullSeeSaw[F] =
        StartsFullSeeSaw(SeeSawSemaphoreImpl(Proxy(fullSemaphore.value), Mate(emptySemaphore.value)))
      override final val startsEmptySeeSaw: StartsEmptySeeSaw[F] =
        StartsEmptySeeSaw(SeeSawSemaphoreImpl(Proxy(emptySemaphore.value), Mate(fullSemaphore.value)))
    }
}
