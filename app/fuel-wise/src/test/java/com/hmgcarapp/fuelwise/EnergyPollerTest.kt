package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.vehicle.Dom
import com.hmgcarapp.fuelwise.vehicle.EnergyCallbacks
import com.hmgcarapp.fuelwise.vehicle.EnergyEngine
import com.hmgcarapp.core.connection.isConnectionFailure
import com.hmgcarapp.fuelwise.vehicle.EnergyPoller
import com.hmgcarapp.fuelwise.vehicle.EnergyPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M6(앱): 초기화 실패 백오프 재시도·연결 오류 시 재생성·취소 시 release 순서(가짜 포트, 가상 시간). */
@OptIn(ExperimentalCoroutinesApi::class)
class EnergyPollerTest {
    private class FakePort(
        val name: String, val log: MutableList<String>,
        val initFails: Boolean = false, val requestError: Throwable? = null,
    ) : EnergyPort {
        override fun initialize() { log += "init $name"; if (initFails) throw IllegalStateException("service not connected") }
        override fun request(d: Dom, id: Int, cb: EnergyCallbacks) {
            log += "req $name $d"
            requestError?.let { throw it }
            when (d) { Dom.FUEL -> cb.fuel(id, 50); Dom.EV -> cb.ev(id, 40); Dom.RANGE -> cb.range(id, 80_000f) }
        }
        override fun release() { log += "release $name" }
    }

    @Test fun connectionFailureClassification() {
        assertTrue(isConnectionFailure(RuntimeException("DeadObjectException: binder died")))
        assertTrue(isConnectionFailure(IllegalStateException("Service not connected")))
        assertTrue(isConnectionFailure(RuntimeException("서비스 연결 끊김")))
        assertFalse(isConnectionFailure(IllegalArgumentException("bad value")))
        assertFalse(isConnectionFailure(IllegalStateException()))
    }

    @Test fun initializeFailure_isRetriedInsideTheLoop_withBackoff() = runTest {
        val log = mutableListOf<String>(); val times = mutableListOf<Long>()
        var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ times += testScheduler.currentTime; n++; FakePort("p$n", log, initFails = n <= 2) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        advanceTimeBy(6_001); runCurrent()
        assertEquals("0s 실패, 2s 실패(+2s), 6s 성공(+4s)", listOf(0L, 2_000L, 6_000L), times)
        assertEquals(listOf("init p1", "release p1", "init p2", "release p2", "init p3"), log.take(5)) // 실패한 포트는 release
        assertTrue("성공 뒤 폴링 시작", log.any { it.startsWith("req p3") })
        assertEquals(50, eng.book.value.reading().fuelPercent)
        job.cancelAndJoin(); poller.closeQuietly()
    }

    @Test fun initFailure_recordsUnavailableError() = runTest {
        val log = mutableListOf<String>()
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ FakePort("p", log, initFails = true) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent()
        assertTrue(eng.book.value.reading().fuelError!!.startsWith("SDK 불가"))
        job.cancelAndJoin(); poller.closeQuietly()
    }

    @Test fun retryNow_afterConnectionError_releasesAndRecreatesPort() = runTest {
        val log = mutableListOf<String>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({
            n++
            if (n == 1) FakePort("p1", log, requestError = RuntimeException("DeadObjectException: service died")) else FakePort("p$n", log)
        }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent()
        assertEquals(1, n)
        poller.retryNow(); runCurrent()
        assertEquals("연결 오류 뒤 재개 시 새로 생성", 2, n)
        val iRelease = log.indexOf("release p1"); val iInit = log.indexOf("init p2")
        assertTrue("기존 포트 release 가 새 initialize 보다 먼저: $log", iRelease in 0 until iInit)
        assertEquals(50, eng.book.value.reading().fuelPercent)
        job.cancelAndJoin(); poller.closeQuietly()
    }

    @Test fun retryNow_afterNonConnectionError_keepsPort() = runTest {
        val log = mutableListOf<String>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ n++; FakePort("p$n", log, requestError = IllegalArgumentException("bad value")) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent(); poller.retryNow(); runCurrent()
        assertEquals("일반 오류는 재생성하지 않는다", 1, n)
        assertFalse(log.any { it.startsWith("release") })
        job.cancelAndJoin(); poller.closeQuietly()
    }

    @Test fun cancel_stopsPolling_thenReleasesExactlyOnce_inThatOrder() = runTest {
        val log = mutableListOf<String>()
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ FakePort("p", log) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        advanceTimeBy(7_000); runCurrent()
        job.cancelAndJoin()
        assertFalse("cancelAndJoin 만으로는 release 하지 않는다(순서: join -> release)", log.any { it.startsWith("release") })
        val before = log.size
        poller.closeQuietly(); poller.closeQuietly()
        assertEquals(listOf("release p"), log.drop(before))
        assertTrue(log.take(before).all { !it.startsWith("release") })
    }
}

/** Fix-F P2: 포트 생성 불가 시 마지막 정상값 만료, 이전 포트의 늦은 콜백 무시, 연결 끊김 자동 재생성 간격. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnergyPollerFixFTest {
    private class P(val name: String, val log: MutableList<String>, val fail: Boolean = false) : EnergyPort {
        var cb: EnergyCallbacks? = null; var lastId = 0
        override fun initialize() { log += "init $name"; if (fail) throw IllegalStateException("boom") }
        override fun request(d: Dom, id: Int, cb: EnergyCallbacks) { this.cb = cb; lastId = id; log += "req $name $d" }
        override fun release() { log += "release $name" }
    }

    @Test fun noPort_withExistingValue_expiresAfterTtl() = runTest {
        val log = mutableListOf<String>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        // 값이 먼저 들어와 있고(마지막 정상값) 이후 포트를 만들 수 없는 상황
        val id = eng.due().first { it.first == Dom.FUEL }.second
        eng.fuelOk(id, 8)
        val poller = EnergyPoller({ n++; P("p$n", log, fail = true) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent(); assertEquals(8, eng.book.value.reading().fuelPercent)
        advanceTimeBy(70_000); runCurrent() // 백오프 대기(2,4,8,16,30초)마다 만료만 검사
        assertEquals("포트 없음 + TTL 경과 -> '차량 신호' 값이 남지 않는다", null, eng.book.value.reading().fuelPercent)
        job.cancelAndJoin(); poller.closeQuietly()
    }

    @Test fun lateCallbackFromReleasedPort_isIgnored_andAutoRecreateIsRateLimited() = runTest {
        val log = mutableListOf<String>(); val ports = mutableListOf<P>()
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ P("p${ports.size + 1}", log).also { ports += it } }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent()
        val p1 = ports[0]
        p1.cb!!.error(Dom.FUEL, p1.lastId, RuntimeException("DeadObjectException")) // 연결 끊김
        advanceTimeBy(3_001); runCurrent()
        assertEquals("처음 끊김은 자동으로 한 번 재생성(기준 없음)", 2, ports.size)
        val p2 = ports[1]
        p1.cb!!.error(Dom.FUEL, 999, RuntimeException("DeadObjectException")) // 해제된 포트의 늦은 오류
        advanceTimeBy(3_001); runCurrent()
        assertEquals("늦은 오류는 새 포트를 재생성하게 만들지 않는다", 2, ports.size)
        p2.cb!!.error(Dom.FUEL, p2.lastId, RuntimeException("service disconnected"))
        advanceTimeBy(30_000); runCurrent()
        assertEquals("60초 이내 재생성 금지", 2, ports.size)
        advanceTimeBy(40_000); runCurrent()
        assertEquals(3, ports.size)
        job.cancelAndJoin(); poller.closeQuietly()
    }
}
