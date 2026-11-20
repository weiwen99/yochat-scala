package yochat.store

import java.nio.file.Path

import cats.effect.unsafe.implicits.global
import cats.effect.IO

/** G14 child: open the store with `--enable-native-access=ALL-UNNAMED` and exit without warnings. */
object NativeProbe:

  def main(args: Array[String]): Unit =
    args.toList match
      case directory :: Nil =>
        val result = Store
          .open(StoreTestSupport.keyFileConfig(Path.of(directory)))
          .use {
            case Left(error)  => IO.pure(Left(error))
            case Right(store) => store.loadSession(StoreTestSupport.sessionIdOf(1)).map(_.map(_ => ()))
          }
          .unsafeRunSync()
        result match
          case Left(error) => System.err.println(error.code); Runtime.getRuntime.halt(2)
          case Right(_)    => Runtime.getRuntime.halt(0)
      case _                => Runtime.getRuntime.halt(2)
