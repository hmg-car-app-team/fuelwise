package com.hmgcarapp.fuelwise

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.fuelwise.app.Persisted
import com.hmgcarapp.fuelwise.app.StateStore
import com.hmgcarapp.fuelwise.domain.MessageRules
import com.hmgcarapp.fuelwise.domain.NaviLineRules
import com.hmgcarapp.fuelwise.domain.StoreRules
import com.hmgcarapp.fuelwise.domain.safelyCatching
import com.hmgcarapp.fuelwise.navi.NaviConnectRules
import com.hmgcarapp.fuelwise.vehicle.Dom
import com.hmgcarapp.fuelwise.vehicle.EnergyCallbacks
import com.hmgcarapp.fuelwise.vehicle.EnergyEngine
import com.hmgcarapp.fuelwise.vehicle.EnergyPoller
import com.hmgcarapp.fuelwise.vehicle.EnergyPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 세션 편집 판정, 삭제 실패 처리, 이중 집계/무응답 복구, 내비 타임아웃 계수, 메시지·팝업 규칙. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionEditAndNaviTimeoutTests {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "fuelwise_state"

    // ---- T1 ----
    // ---- 삭제 ----
    @Test fun clearAll_returnsFalseAndKeepsHold_whenPurgeFails() {
        val d = File(tmp.root, "s").also { it.mkdirs() }
        File(d, "$name.json").also { it.mkdirs(); File(it, "child").writeText("x") } // 비어 있지 않은 디렉터리: 삭제 불가
        val ss = StateStore(LocalJsonStore(d)); ss.restore(1_000)
        assertFalse(ss.writable)
        assertFalse("삭제 실패는 false", ss.clearAll { Persisted() })
        assertFalse("실패 시 저장 보류 유지", ss.writable)
    }

    @Test fun clearAll_returnsTrueOnSuccess() {
        val d = File(tmp.root, "s2").also { it.mkdirs() }
        val ss = StateStore(LocalJsonStore(d))
        assertEquals(ss.writeLatest { Persisted(selectedId = "a") }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        assertTrue(ss.clearAll { Persisted() })
        assertTrue(ss.writable)
    }

    // ---- 메시지/팝업/내비 줄 ----
    @Test fun messageSet_suppressedWhileDriving() {
        assertEquals("기존", MessageRules.set("기존", "새 안내", driving = true))
        assertNull(MessageRules.set(null, "새 안내", driving = true))
        assertEquals("새 안내", MessageRules.set("기존", "새 안내", driving = false))
    }

    @Test fun clearPopupText_isShortEnough_andKeepsIrreversibleNote() {
        for ((n, held, kept) in listOf(Triple(3, false, false), Triple(120, true, false), Triple(0, true, true), Triple(5, false, true))) {
            val t = StoreRules.clearDescription(n, held, kept)
            assertTrue(t, t.endsWith("되돌릴 수 없어요."))
            assertTrue(t, t.split("\n").all { it.length <= 22 }) // 줄당 짧게: 3줄 말줄임에 걸리지 않음
        }
    }

    @Test fun naviLine_shownOnceBannerWins() {
        assertFalse(NaviLineRules.showInDetail(bannerVisible = true))
        assertTrue(NaviLineRules.showInDetail(bannerVisible = false))
        assertEquals("요청 실패", NaviConnectRules.shortWord(com.hmgcarapp.fuelwise.navi.NaviStatus.Failed("아주 긴 사유 ".repeat(5), 1)))
    }

    // ---- r6R5-9 ----
    @Test fun safelyCatching_catchesThrowable_butRethrowsCancellation() {
        var seen: Throwable? = null
        safelyCatching({ seen = it }) { throw NoClassDefFoundError("x") } // VirtualMachineError(OOM 등)는 재던진다(DeleteFailureRestoreTests)
        assertTrue(seen is NoClassDefFoundError)
        var threw = false
        try { safelyCatching({ seen = null }) { throw CancellationException("c") } } catch (e: CancellationException) { threw = true }
        assertTrue(threw)
    }

    // ---- 엔진 ----
    @Test fun duplicateErrorCallbacks_countOnlyOnceAgainstTheGate() {
        var t = 0L
        val eng = EnergyEngine({ t })
        repeat(3) { // 요청마다 오류 콜백이 2번 와도 1회로 센다: 3회면 정지(5회 미만)하지 않음
            val id = eng.due().first { it.first == Dom.FUEL }.second
            eng.fail(Dom.FUEL, id, "e"); eng.fail(Dom.FUEL, id, "e")
            t += 3_000
        }
        assertFalse(eng.isPaused(Dom.FUEL))
        repeat(2) { val id = eng.due().first { it.first == Dom.FUEL }.second; eng.fail(Dom.FUEL, id, "e"); t += 3_000 }
        assertTrue("정확히 5회째 정지", eng.isPaused(Dom.FUEL))
    }

    @Test fun clearPending_doesNotRecordFailure() {
        var t = 0L
        var silent = 0
        val eng = EnergyEngine({ t }).also { it.onSilentCycle = { silent++ } }
        eng.due(); eng.clearPending()
        t = 6_000
        eng.due()
        assertNull("응답 없음 오류가 기록되지 않음", eng.book.value.fuel.error)
        assertEquals("무응답 주기로 세지 않음", 0, silent)
    }

    @Test fun engine_reportsSilentCycle_whenARequestTimesOut() {
        var t = 0L
        var silent = 0
        val eng = EnergyEngine({ t }).also { it.onSilentCycle = { silent++ } }
        eng.due(); t = 4_999; eng.due(); assertEquals(0, silent)
        t = 5_000; eng.due(); assertEquals("5초 무응답 -> 주기 1회", 1, silent)
    }

    private class Silent(val name: String, val log: MutableList<String>) : EnergyPort {
        override fun initialize() { log += "init $name" }
        override fun request(d: Dom, id: Int, cb: EnergyCallbacks) = Unit
        override fun release() { log += "release $name" }
    }

    @Test fun repeatedRetryNow_keepsSilenceFlag_andReplacesSilentPort() = runTest {
        val log = mutableListOf<String>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ Silent("p${++n}", log) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent(); advanceTimeBy(6_000); runCurrent() // 무응답 1주기 이상
        poller.retryNow(); poller.retryNow(); runCurrent() // 두 번째 호출이 플래그를 덮어쓰면 교체되지 않던 문제
        assertEquals("무응답 포트는 교체", 2, n)
        job.cancelAndJoin(); poller.closeQuietly()
    }
}
