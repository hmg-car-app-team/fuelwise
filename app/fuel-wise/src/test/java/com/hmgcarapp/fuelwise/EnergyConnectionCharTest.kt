package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.vehicle.Dom
import com.hmgcarapp.fuelwise.vehicle.EnergyCallbacks
import com.hmgcarapp.fuelwise.vehicle.EnergyEngine
import com.hmgcarapp.fuelwise.vehicle.EnergyPoller
import com.hmgcarapp.fuelwise.vehicle.EnergyPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Refactor-A 특성화: 에너지 포트 연결 계약(리팩터링 전 구현에 대해 먼저 통과, 새 구조에서도 유지). */
@OptIn(ExperimentalCoroutinesApi::class)
class EnergyConnectionCharTest {
    private class P(val name: String, val log: MutableList<String>, val initFails: Boolean = false, val deadOnRequest: Boolean = false) : EnergyPort {
        override fun initialize() { log += "init $name"; if (initFails) throw IllegalStateException("service not connected") }
        override fun request(d: Dom, id: Int, cb: EnergyCallbacks) {
            if (deadOnRequest) { cb.error(d, id, RuntimeException("DeadObjectException: service died")); return }
            when (d) { Dom.FUEL -> cb.fuel(id, 50); Dom.EV -> cb.ev(id, 40); Dom.RANGE -> cb.range(id, 80_000f) }
        }
        override fun release() { log += "release $name" }
    }

    @Test fun manualRetry_replacesDeadPortImmediately_evenWithinAutoRecreateInterval() = runTest {
        val log = mutableListOf<String>(); val ports = mutableListOf<P>()
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ P("p${ports.size + 1}", log, deadOnRequest = true).also { ports += it } }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent(); advanceTimeBy(3_001); runCurrent()
        assertEquals("처음 끊김은 즉시 자동 재생성", 2, ports.size)
        advanceTimeBy(20_000); runCurrent()
        assertEquals("60초 이내 자동 재생성 금지", 2, ports.size)
        poller.retryNow(); runCurrent()
        assertEquals("수동 재시도는 간격과 무관하게 즉시 교체", 3, ports.size)
        job.cancelAndJoin(); poller.closeQuietly()
    }

    @Test fun initBackoff_restartsAt2s_afterAPortWasDropped() = runTest {
        val log = mutableListOf<String>(); val times = mutableListOf<Long>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        // 1번 정상(요청 오류로 끊김) -> 수동 재시도로 교체 -> 2,3번 초기화 실패 -> 4번 성공: 대기는 2초, 4초(8초가 아님)
        val poller = EnergyPoller({
            times += testScheduler.currentTime; n++
            P("p$n", log, initFails = n in 2..3, deadOnRequest = n == 1)
        }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent()
        poller.retryNow(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(0L, 0L, 2_000L, 6_000L), times)
        job.cancelAndJoin(); poller.closeQuietly()
    }
}
