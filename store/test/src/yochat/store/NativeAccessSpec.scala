package yochat.store

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import munit.FunSuite

class NativeAccessSpec extends FunSuite:

  test("G14: JDK native access flag lets the store open without a restricted-method warning") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-native-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withKeyFileStore(directory) { store =>
        for
          _ <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _ <- store.insertSession(session, 0L)
        yield ()
      }
      val output  = runProbe(directory)
      assert(!output.toLowerCase.contains("restricted method"), s"native warning leaked: $output")
    finally StoreTestSupport.deleteTree(directory)
  }

  private def runProbe(directory: Path): String =
    val java     = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val command  = List(
      java,
      "--enable-native-access=ALL-UNNAMED",
      "-cp",
      System.getProperty("java.class.path"),
      "yochat.store.NativeProbe",
      directory.toString
    ).asJava
    val process  = new ProcessBuilder(command).redirectErrorStream(true).start()
    val finished = process.waitFor(20L, TimeUnit.SECONDS)
    if !finished then process.destroyForcibly()
    val output   = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    assertEquals(process.exitValue(), 0, s"native probe failed: $output")
    output
