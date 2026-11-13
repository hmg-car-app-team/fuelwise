package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.data.CandidateLoader
import com.hmgcarapp.fuelwise.data.StationRepository
import com.hmgcarapp.fuelwise.domain.AlertGate
import com.hmgcarapp.fuelwise.domain.AlertPolicy
import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.BannerRules
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.MinInterval
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoaderAndGateTest {
    private fun cand(id: String) = Candidate(id, id, "X", EnergyKind.FUEL, LatLon(37.5, 127.0))

    /** K7(a): 먼저 시작한(기본 위치) 로드가 늦게 끝나도 나중에 시작한(실위치) 결과를 덮어쓰지 못한다. */
    @Test fun staleLoad_cannotOverwriteNewerOrigin() = runTest {
        val gates = mapOf("old" to CompletableDeferred<List<Candidate>>(), "new" to CompletableDeferred())
        val repo = object : StationRepository {
            override suspend fun candidates(origin: LatLon) = gates.getValue(if (origin.lat < 37.4) "old" else "new").await()
        }
        val loader = CandidateLoader(repo)
        val oldLoad = async { loader.load(LatLon(37.3, 127.0)) }
        val newLoad = async { loader.load(LatLon(37.5, 127.0)) }
        gates.getValue("new").complete(listOf(cand("new")))
        assertEquals(listOf("new"), newLoad.await()!!.map { it.id })
        gates.getValue("old").complete(listOf(cand("old")))
        assertNull("오래된 결과는 버려진다", oldLoad.await())
        assertEquals(listOf("new"), loader.candidates.map { it.id })
        assertEquals(LatLon(37.5, 127.0), loader.cache.lastLoadOrigin)
    }

    @Test fun olderLoad_applies_ifNewerOneFailed() = runTest {
        val newGate = CompletableDeferred<List<Candidate>>(); val oldGate = CompletableDeferred<List<Candidate>>()
        val repo = object : StationRepository {
            override suspend fun candidates(origin: LatLon) = if (origin.lat < 37.4) oldGate.await() else newGate.await()
        }
        val loader = CandidateLoader(repo)
        val a = async { loader.load(LatLon(37.3, 127.0)) }
        val b = async { loader.load(LatLon(37.5, 127.0)) }
        newGate.completeExceptionally(IllegalStateException("boom"))
        assertNull(b.await())
        oldGate.complete(listOf(cand("old")))
        assertEquals(listOf("old"), a.await()!!.map { it.id })
    }

    /** K7(b): 후보가 비어 있는 동안 30초마다 재시도(주차 중에도). */
    @Test fun retryDue_every30s_whileEmpty_thenStops() = runTest {
        var now = 0L; var fail = true
        val repo = object : StationRepository {
            override suspend fun candidates(origin: LatLon): List<Candidate> { if (fail) throw IllegalStateException("net"); return listOf(cand("a")) }
        }
        val loader = CandidateLoader(repo, clock = { now })
        assertTrue("시도 전에는 재시도 대상", loader.retryDue())
        assertNull(loader.load(LatLon(37.5, 127.0)))
        now = 29_999; assertFalse(loader.retryDue())
        now = 30_000; assertTrue(loader.retryDue())
        fail = false
        assertEquals(1, loader.load(LatLon(37.5, 127.0))!!.size)
        now = 90_000; assertFalse("후보가 있으면 재시도하지 않음", loader.retryDue())
    }

    /** K7(c)/M4: 로드 전·로드 실패 중에는 알림을 보류하고 armed 를 소진하지 않는다. */
    @Test fun alertHeld_untilLoadDone_andArmedNotConsumed() {
        val fire = AlertPolicy.Decision(true, false, "잔량 15%")
        assertEquals(AlertPolicy.Decision(false, true, null), AlertGate.hold(fire, loadDone = false, hasBest = true, currentArmed = true))
        assertEquals("로드 전에는 후보 유무와 관계없이 보류", AlertPolicy.Decision(false, true, null), AlertGate.hold(fire, loadDone = false, hasBest = false, currentArmed = true))
        assertEquals(fire, AlertGate.hold(fire, loadDone = true, hasBest = true, currentArmed = true))
        // 이미 울린 뒤(armed=false)에는 보류가 armed 를 되살리지 않는다
        assertEquals(AlertPolicy.Decision(false, false, null), AlertGate.hold(AlertPolicy.Decision(false, false, null), false, false, false))
    }

    /** M4: 로드 완료인데 후보 0개면 잔량 임계에서 1회 '후보 없음' 알림(armed 소진). 임계 위/이미 울린 뒤에는 울리지 않는다. */
    @Test fun noCandidateAlert_onceAfterLoadDone() {
        val fire = AlertPolicy.Decision(true, false, "잔량 15%")
        val d = AlertGate.hold(fire, loadDone = true, hasBest = false, currentArmed = true)
        assertTrue(d.trigger); assertFalse("armed 소진", d.armed); assertEquals("잔량 부족", d.reason)
        // 정책 단계: 임계 위이면 trigger 없음 -> 보류 규칙이 알림을 만들지 않는다
        val ok = AlertPolicy.evaluate(50, null, 20, 60, true)
        assertFalse(AlertGate.hold(ok, true, false, true).trigger)
        // 한 번 울린 뒤(armed=false)에는 같은 상태에서 다시 울리지 않는다
        val after = AlertPolicy.evaluate(15, null, 20, 60, false)
        assertFalse(AlertGate.hold(after, true, false, false).trigger)
        // 문구
        assertEquals("잔량 부족.", AlertText.voice(AlertGate.NO_CANDIDATE_REASON, null, "주유", ""))
        assertEquals("잔량 부족", AlertText.line(AlertGate.NO_CANDIDATE_REASON, null))
    }

    /** M4: 로더는 '후보 0개 성공'과 '로드 실패'를 구분한다. */
    @Test fun loader_loadedOnce_distinguishesEmptySuccessFromFailure() = runTest {
        var fail = true
        val repo = object : StationRepository {
            override suspend fun candidates(origin: LatLon): List<Candidate> { if (fail) throw IllegalStateException("net"); return emptyList() }
        }
        val loader = CandidateLoader(repo)
        assertFalse(loader.loadedOnce)
        assertNull(loader.load(LatLon(37.5, 127.0)))
        assertFalse("실패만 있었으면 로드 완료 아님", loader.loadedOnce)
        fail = false
        assertEquals(0, loader.load(LatLon(37.5, 127.0))!!.size)
        assertTrue("후보 0개여도 로드는 완료", loader.loadedOnce); assertFalse(loader.loaded)
    }

    /** C2: 시계가 뒤로 가도(last > now) 재시도 대기·디바운스가 멈추지 않는다. */
    @Test fun backwardsClock_doesNotFreezeRetryOrDebounce() = runTest {
        var now = 100_000L
        val repo = object : StationRepository { override suspend fun candidates(origin: LatLon): List<Candidate> = throw IllegalStateException("net") }
        val loader = CandidateLoader(repo, clock = { now })
        loader.load(LatLon(37.5, 127.0))
        now = 50_000L
        assertTrue("시계가 뒤로 가면 즉시 재시도 대상", loader.retryDue())
        val m = MinInterval(300)
        assertTrue(m.accept(10_000)); assertTrue("뒤로 간 시각은 거부하지 않는다", m.accept(5_000)); assertFalse(m.accept(5_100)); assertTrue(m.accept(5_300))
    }

    /** C12: 음성 흐름(말하는 중/듣는 중) 중 release 면 armed 를 되돌린다. */
    @Test fun armedOnRelease_restoresOnlyWhileVoiceFlowUnfinished() {
        assertTrue(BannerRules.armedOnRelease(VoiceStatus.SPEAKING, false))
        assertTrue(BannerRules.armedOnRelease(VoiceStatus.LISTENING, false))
        listOf(VoiceStatus.ACCEPTED, VoiceStatus.DECLINED, VoiceStatus.FAILED, VoiceStatus.NO_REPLY, null)
            .forEach { assertFalse(it ?: "null", BannerRules.armedOnRelease(it, false)) }
        assertTrue("이미 armed 면 그대로", BannerRules.armedOnRelease(null, true))
    }

    // ---- K9 배너 규칙 ----
    @Test fun bannerRules() {
        assertFalse(BannerRules.isTerminal(VoiceStatus.SPEAKING)); assertFalse(BannerRules.isTerminal(VoiceStatus.LISTENING))
        listOf(VoiceStatus.ACCEPTED, VoiceStatus.DECLINED, VoiceStatus.FAILED, VoiceStatus.NO_REPLY).forEach { assertTrue(it, BannerRules.isTerminal(it)) }
        assertTrue(BannerRules.clearOnRearm(false, true)); assertFalse(BannerRules.clearOnRearm(true, true)); assertFalse(BannerRules.clearOnRearm(false, false))
        assertEquals("a", BannerRules.staleSafe("a", listOf("a", "b"))); assertNull(BannerRules.staleSafe("z", listOf("a"))); assertNull(BannerRules.staleSafe(null, listOf("a")))
    }

    // ---- K10 ----
    @Test fun minInterval_debounces300ms() {
        val m = MinInterval(300)
        assertTrue(m.accept(1_000)); assertFalse(m.accept(1_299)); assertTrue(m.accept(1_300)); assertFalse(m.accept(1_301))
    }
}
