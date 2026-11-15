package com.hmgcarapp.fuelwise

import com.hmgcarapp.core.data.DataSourceInfo
import com.hmgcarapp.core.data.DataSourceKind
import com.hmgcarapp.fuelwise.data.StationRepositories
import com.hmgcarapp.fuelwise.domain.AlertGate
import com.hmgcarapp.fuelwise.domain.AlertPolicy
import com.hmgcarapp.fuelwise.domain.BannerRules
import com.hmgcarapp.fuelwise.domain.MessageRules
import com.hmgcarapp.fuelwise.domain.StoreRules
import com.hmgcarapp.fuelwise.domain.VoiceOutcome
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import com.hmgcarapp.fuelwise.navi.NaviReducer
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.ui.drivingStatusLine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서비스 재시작 복구(무응답 포트 교체·내비 재연결 규칙)·정직 라벨·저장 복구 규칙·판단 로직 순수 함수. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceRestartRecoveryTests {
    // ---------- S3: EnergyPoller 무응답 연속 -> 포트 교체 ----------
    private class Silent(val name: String, val log: MutableList<String>, val answers: Boolean = false) : EnergyPort {
        override fun initialize() { log += "init $name" }
        override fun request(d: Dom, id: Int, cb: EnergyCallbacks) {
            if (answers) when (d) { Dom.FUEL -> cb.fuel(id, 50); Dom.EV -> cb.ev(id, 40); Dom.RANGE -> cb.range(id, 80_000f) }
        }
        override fun release() { log += "release $name" }
    }

    @Test fun silentPort_isReplacedAfterThreeSilentCycles_notBefore() = runTest {
        val log = mutableListOf<String>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ n++; Silent("p$n", log) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent(); advanceTimeBy(11_000); runCurrent() // 무응답 2주기(6초, 12초 직전)
        assertEquals("2주기까지는 교체하지 않는다", 1, n)
        advanceTimeBy(14_000); runCurrent()
        assertTrue("무응답 3주기 연속이면 포트 교체: $log", n >= 2)
        assertTrue(log.indexOf("release p1") in 0 until log.indexOf("init p2"))
        job.cancelAndJoin(); poller.closeQuietly()
    }

    @Test fun manualRetry_dropsSilentPort_butKeepsHealthyPort() = runTest {
        val log = mutableListOf<String>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ n++; Silent("p$n", log) }, eng, 3_000, nowMs = { testScheduler.currentTime })
        val job = launch { poller.run() }
        runCurrent(); advanceTimeBy(7_000); runCurrent() // 무응답 1주기
        assertEquals(1, n)
        poller.retryNow(); runCurrent()
        assertEquals("수동 재시도는 무응답이었던 포트를 교체", 2, n)
        job.cancelAndJoin(); poller.closeQuietly()

        val log2 = mutableListOf<String>(); var m = 0
        val eng2 = EnergyEngine({ testScheduler.currentTime })
        val poller2 = EnergyPoller({ m++; Silent("q$m", log2, answers = true) }, eng2, 3_000, nowMs = { testScheduler.currentTime })
        val job2 = launch { poller2.run() }
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        poller2.retryNow(); runCurrent()
        assertEquals("정상 응답 중인 포트는 수동 재시도로 교체하지 않는다", 1, m)
        job2.cancelAndJoin(); poller2.closeQuietly()
    }

    @Test fun naviSettleFor_clearsOnlyThatRequest() {
        assertEquals(NaviStatus.Idle, NaviReducer.settleFor(NaviStatus.Success("x", 3), 3))
        assertEquals(NaviStatus.Idle, NaviReducer.settleFor(NaviStatus.Failed("x", 3), 3))
        val other = NaviStatus.Success("x", 4)
        assertEquals("다른 요청의 결과는 유지", other, NaviReducer.settleFor(other, 3))
        val pending = NaviStatus.Pending("p", 3)
        assertEquals("처리 중 요청은 유지", pending, NaviReducer.settleFor(pending, 3))
        assertEquals(other, NaviReducer.settleFor(other, NaviReducer.IGNORED))
    }

    @Test fun drivingStatusLine_keepsNaviWord_underLengthLimit() {
        val longReason = NaviStatus.Failed("경로를 찾지 못했어요 그래서 아주 긴 사유가 붙어도", 1)
        val line = drivingStatusLine(VoiceStatus.NOT_ACCEPTED, longReason)
        assertTrue(line, line.length <= 40 && line.endsWith("요청 실패"))
        assertEquals("진행형 음성 상태는 숨김", "", drivingStatusLine(VoiceStatus.SPEAKING, NaviStatus.Idle))
        assertEquals("", drivingStatusLine(VoiceStatus.LISTENING, NaviStatus.Idle))
        assertEquals("음성 수락", drivingStatusLine(VoiceStatus.ACCEPTED, NaviStatus.Idle))
        assertEquals("요청 중", drivingStatusLine(VoiceStatus.SPEAKING, NaviStatus.Pending("p", 1)))
    }

    // ---------- S2 ----------
    @Test fun dataLabel_isAlwaysSample_evenWhenRemoteSelected() {
        val remote = DataSourceInfo(DataSourceKind.REMOTE, "실시간 데이터")
        assertEquals("가공 샘플", StationRepositories.honestInfo(remote).label)
        assertEquals(DataSourceKind.FAKE, StationRepositories.honestInfo(remote).kind)
        assertEquals("가공 샘플", StationRepositories.honestInfo(DataSourceInfo(DataSourceKind.FAKE, "샘플 데이터(원격 실패)")).label)
    }

    // ---------- S1 ----------
    @Test fun clearButton_enabledByRecordsHeldOrKeptCorrupt() {
        assertFalse(StoreRules.clearEnabled(0, saveHeld = false, corruptKept = false))
        assertTrue(StoreRules.clearEnabled(2, false, false))
        assertTrue(StoreRules.clearEnabled(0, saveHeld = true, corruptKept = false))
        assertTrue(StoreRules.clearEnabled(0, saveHeld = false, corruptKept = true))
        assertEquals("기록 3건이 삭제돼요.\n되돌릴 수 없어요.", StoreRules.clearDescription(3, false, false))
        assertTrue(StoreRules.clearDescription(0, true, false).contains("저장 파일"))
        assertTrue(StoreRules.clearDescription(2, false, true).let { it.contains("2건") && it.contains("저장 파일") })
    }

    // ---------- r5R2-8 판단 로직 ----------
    @Test fun alertGateDecide_combinesPolicyAndHold() {
        fun d(level: Int?, armed: Boolean = true, loadDone: Boolean = true, hasBest: Boolean = true, origin: Boolean = true) =
            AlertGate.decide(level, null, 20, 60, armed, loadDone, hasBest, origin)
        assertTrue(d(15).trigger && !d(15).armed)
        assertFalse("로드 전 보류: armed 소진 금지", d(15, loadDone = false).trigger)
        assertTrue(d(15, loadDone = false).armed)
        assertFalse("실위치 전 보류(N1)", d(15, origin = false).trigger)
        assertTrue(d(15, origin = false).armed)
        val noBest = d(15, hasBest = false)
        assertTrue(noBest.trigger && noBest.reason == AlertGate.NO_CANDIDATE_REASON && !noBest.armed)
        assertFalse(d(50).trigger)
        assertEquals(AlertPolicy.evaluate(50, null, 20, 60, true), d(50))
    }

    @Test fun voiceOutcome_mapsTextToStatus() {
        assertEquals(VoiceStatus.ACCEPTED, VoiceOutcome.of("네", canOverwrite = false))
        assertEquals(VoiceStatus.DECLINED, VoiceOutcome.of("아니요", false))
        assertEquals(VoiceStatus.NOT_ACCEPTED, VoiceOutcome.of("네", canOverwrite = true))
        assertEquals(VoiceStatus.ACCEPTED, VoiceOutcome.of("바꿔줘", canOverwrite = true))
        assertEquals("빈 STT 결과는 무응답", VoiceStatus.NO_REPLY, VoiceOutcome.of("", false))
        assertEquals(VoiceStatus.NO_REPLY, VoiceOutcome.of("  ?! ", true))
        assertEquals(VoiceStatus.DECLINED, VoiceOutcome.of("오늘 날씨 어때", false))
    }

    @Test fun armedOnRelease_alsoWhileAlertInFlight() {
        assertTrue("배너가 뜨기 전 경로 확인 중", BannerRules.armedOnRelease(null, armed = false, alertInFlight = true))
        assertFalse(BannerRules.armedOnRelease(null, armed = false, alertInFlight = false))
    }

    @Test fun message_isDroppedWhenDrivingStarts() {
        assertNull(MessageRules.onDriveChange("기록됨", nowDriving = true))
        assertEquals("기록됨", MessageRules.onDriveChange("기록됨", nowDriving = false))
        assertNull(MessageRules.onDriveChange(null, false))
    }
}
