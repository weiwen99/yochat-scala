package yochat.server

import cats.effect.IO
import munit.FunSuite

import yochat.core.s2.PeerDeviceList

final class PeerDeviceListServiceSpec extends FunSuite:

  test("S2-B capability is absent until a companion or peer declaration is observed") {
    ExecutorTestSupport.withStore { store =>
      for
        before <- PeerDeviceListService.build(store, 1_000L)
        _      <- store.markS2bSeen
        after  <- PeerDeviceListService.build(store, 2_000L)
      yield
        assertEquals(before.map(_.features), Right(0L))
        assertEquals(after.map(_.features), Right(PeerDeviceList.FeatureS2b))
        IO.unit
    }
  }
