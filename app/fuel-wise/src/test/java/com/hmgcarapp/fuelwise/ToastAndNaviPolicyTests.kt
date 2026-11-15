package com.hmgcarapp.fuelwise

import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.fuelwise.domain.StoreRules
import com.hmgcarapp.fuelwise.navi.NaviConnectRules
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 토스트 문구 판정, Navi 연결 정책 값, 오류 콜백 뒤 무응답 카운트 리셋. */
@OptIn(ExperimentalCoroutinesApi::class)
class ToastAndNaviPolicyTests {
    // ---- r8R1-3 ----
    @Test fun recordedMessage_sameWordingRegardlessOfSaveHeld() {
        assertEquals("기록됨: 강남 센트럴 주유소", StoreRules.recordedMessage("강남 센트럴 주유소", 1350L, saveHeld = false))
        val held = StoreRules.recordedMessage("강남 센트럴 주유소", 1350L, saveHeld = true)
        assertEquals("기록됨: 강남 센트럴 주유소", held)
        assertFalse(held.contains("이번 실행에서만"))
    }

    // ---- r8R2-2: Navi 정책값 고정(동작 불변) ----
    private class H(val id: Int)

    private class Env {
        var now = 0L
        val created = mutableListOf<H>(); val disposed = mutableListOf<Int>()
        val conn = ManagedConnection(
            tag = "T", create = { H(created.size + 1).also { created += it } }, dispose = { disposed += it.id },
            clock = { now }, policy = NaviConnectRules.POLICY, log = { },
        )
    }
    private val dead = RuntimeException("service disconnected")

    @Test fun naviPolicy_values() {
        val p = NaviConnectRules.POLICY
        assertEquals(10_000L, p.readyTimeoutMs); assertEquals(2, p.silenceCycles)
        assertFalse(p.deathWaitsBackoff); assertEquals(0L, p.backoffStartMs); assertEquals(0L, p.minReplaceIntervalMs)
    }

    @Test fun naviPolicy_stalledUntilReady_isReplacedAtExactly10s_andImmediately() {
        val e = Env()
        val c1 = e.conn.connect()!!                       // 준비 콜백(markReady) 없음
        e.now = 9_999; assertSame("10초 전에는 유지", c1, e.conn.connect())
        e.now = 10_000
        val c2 = e.conn.connect()!!
        assertEquals("정체 10초에 즉시 교체(백오프 없음)", 2, c2.value.id); assertEquals(listOf(1), e.disposed)
        e.conn.markReady(c2.gen); e.now = 100_000
        assertSame("준비된 연결은 정체로 보지 않는다", c2, e.conn.connect())
    }

    @Test fun naviPolicy_twoConsecutiveTimeouts_kill_andDeathReconnectsWithoutWaiting() {
        val e = Env()
        val c1 = e.conn.connect()!!; e.conn.markReady(c1.gen)
        assertFalse(e.conn.onSilentCycle())
        assertTrue("연속 2회째 사망", e.conn.onSilentCycle())
        assertEquals("대기 없이 즉시 재생성", 0L, e.conn.retryDelayMs().also { assertNotNull(e.conn.connect()) })
        assertEquals(2, e.created.size)
        e.conn.markReady(e.conn.current()!!.gen)
        e.conn.onFailure(e.conn.current()!!.gen, dead)
        assertEquals("연결 실패도 즉시 재생성", 3, e.conn.connect()!!.value.id)
    }

    // ---- r8R2-4: 오류 콜백이 무응답 카운트를 0으로 되돌린다(3회째가 아님) ----
    private class P(val log: MutableList<String>) : EnergyPort {
        var cb: EnergyCallbacks? = null
        override fun initialize() { log += "init" }
        override fun request(d: Dom, id: Int, cb: EnergyCallbacks) { this.cb = cb }
        override fun release() { log += "release" }
    }

    @Test fun errorCallback_resetsSilentCount_soReplacementNeedsThreeMoreCycles() = runTest {
        val log = mutableListOf<String>(); val ports = mutableListOf<P>()
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ P(log).also { ports += it } }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent(); advanceTimeBy(12_500); runCurrent()   // 무응답 주기 2회(6초·12초)
        assertEquals(1, ports.size)
        ports[0].cb!!.error(Dom.FUEL, 1, IllegalArgumentException("bad value")) // 서비스 응답(연결 실패 아님) -> 카운트 0
        advanceTimeBy(12_500); runCurrent()                // 25초: 리셋이 없었다면 18초 주기(3회째) 뒤 21초에 이미 교체됐다
        assertEquals("오류 콜백 뒤 3회째가 아니다: 아직 교체 안 함", 1, ports.size)
        advanceTimeBy(12_000); runCurrent()                // 37초: 리셋 뒤 3회째(30초 주기)로 교체
        assertEquals("리셋 뒤 3회째에 교체", 2, ports.size)
        job.cancelAndJoin(); poller.closeQuietly()
    }

    // ---- r9R5-1: 저장본 armed 보정 ----
    @Test fun persistedArmed_inFlightOrVoiceActive_savesTrue_otherwiseKeepsValue_andIsIdempotent() {
        val f = StoreRules::persistedArmed
        assertTrue(f(com.hmgcarapp.fuelwise.domain.VoiceStatus.SPEAKING, false, false))
        assertTrue(f(com.hmgcarapp.fuelwise.domain.VoiceStatus.LISTENING, false, false))
        assertTrue("배너 전 경로 확인 중", f(null, false, true))
        assertFalse("종료 후", f(com.hmgcarapp.fuelwise.domain.VoiceStatus.ACCEPTED, false, false))
        assertFalse("알림 없음", f(null, false, false))
        assertTrue("알림 없음·armed 유지", f(null, true, false))
        val once = f(com.hmgcarapp.fuelwise.domain.VoiceStatus.SPEAKING, false, true)
        assertEquals("release() 의 보정과 겹쳐도 같은 결과", once, f(null, once, true))
    }

    // ---- Fix-L ----
    @get:org.junit.Rule val tmpL = org.junit.rules.TemporaryFolder()

    @Test fun persistedArmedChanged_startKeepsTrue_endFlipsOnce_noAlertOrRearmUnchanged_idempotent() {
        val V = com.hmgcarapp.fuelwise.domain.VoiceStatus
        val pa = StoreRules::persistedArmed
        val ch = StoreRules::persistedArmedChanged
        // 알림 시작: armed true->false, inFlight 시작 -> 보정으로 true 유지
        assertFalse(ch(pa(null, true, false), pa(V.SPEAKING, false, true)))
        // 알림 종료: inFlight 해제·terminal 에서 true -> false
        assertTrue(ch(pa(V.SPEAKING, false, true), pa(V.ACCEPTED, false, false)))
        // 알림 없음 / 재무장
        assertFalse(ch(pa(null, false, false), pa(null, false, false)))
        assertTrue("재무장(false->true)도 값이 바뀐 것: 저장 예약", ch(pa(null, false, false), pa(null, true, false)))
        // 연속 호출 멱등
        val end = pa(V.ACCEPTED, false, false)
        assertFalse(ch(end, pa(V.ACCEPTED, false, false)))
    }

    @Test fun restore_removesLeftoverTmp_evenIfFresh() {
        val d = File(tmpL.root, "t").also { it.mkdirs() }
        val t = File(d, "fuelwise_state.json.123.tmp").also { it.writeText("x"); it.setLastModified(1_000) }
        val ss = com.hmgcarapp.fuelwise.app.StateStore(com.hmgcarapp.core.store.LocalJsonStore(d))
        ss.restore(1_000) // 나이 0 ms
        assertFalse(t.exists())
    }

    @Test fun clearDescription_lastBranch_isNeutralForReadAndWriteHold() {
        val t = StoreRules.clearDescription(0, true, false)
        assertEquals("저장 파일이 삭제돼요.\n되돌릴 수 없어요.", t)
        assertFalse(t.contains("읽지 못한"))
    }
}
