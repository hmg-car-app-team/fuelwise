package com.hmgcarapp.fuelwise

import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.fuelwise.app.AlertRunner
import com.hmgcarapp.fuelwise.app.UiState
import com.hmgcarapp.fuelwise.domain.AlertEffect
import com.hmgcarapp.fuelwise.domain.AlertEvent
import com.hmgcarapp.fuelwise.domain.AlertFlow
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.Destination
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.fuelwise.navi.NaviAction
import com.hmgcarapp.fuelwise.navi.NaviPort
import com.hmgcarapp.fuelwise.navi.NaviReducer
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.NaviText
import com.hmgcarapp.fuelwise.navi.RouteAdvisor
import com.hmgcarapp.fuelwise.navi.RouteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refactor-C: 실제 AlertRunner + 가짜 TTS/STT/내비로 효과 -> 이벤트 되먹임 배선을 가상 시간에서 검증한다(컨트롤러의 dispatch 와 같은 루프). */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertRunnerTest {
    private class FakeSpeaker(val onSpeak: suspend (String) -> Boolean = { delay(1_000); true }) : Speaker {
        val spoken = mutableListOf<String>(); var stops = 0
        override fun speak(text: String) = true
        override suspend fun speakAndAwait(text: String, timeoutMs: Long): Boolean { spoken += text; return onSpeak(text) }
        override fun stop() { stops++ }
        override fun release() {}
    }
    private class FakeListener(val onListen: suspend () -> ListenResult) : Listener {
        var calls = 0
        override suspend fun listenOnce(timeoutMs: Long): ListenResult { calls++; return onListen() }
        override fun release() {}
    }
    private class FakeNavi : NaviPort {
        var awaitDelayMs = 0L
        var status: NaviStatus = NaviStatus.Idle; val requests = mutableListOf<Pair<String, NaviAction>>(); private var seq = 0L
        override suspend fun routeViaCandidate(dest: Destination?, c: Candidate, action: NaviAction): Long {
            requests += c.id to action; val id = ++seq; status = NaviStatus.Success(NaviText.ROUTE_STARTED, id); return id
        }
        override suspend fun awaitResult(id: Long, timeoutMs: Long): NaviStatus? { if (awaitDelayMs > 0) delay(awaitDelayMs); return NaviReducer.resultFor(status, id) }
        override fun clearFinished() { status = NaviReducer.settle(status) }
        override fun clearFinished(id: Long) { status = NaviReducer.settleFor(status, id) }
    }

    private class Rig(scope: CoroutineScope, val speaker: FakeSpeaker, val listener: FakeListener, val navi: FakeNavi = FakeNavi(), mode: RouteMode = RouteMode.NONE, voiceReply: Boolean = true) {
        val q = AlertFlowTest.quote()
        var ui = UiState(
            p = com.hmgcarapp.fuelwise.app.Persisted().let { it.copy(settings = it.settings.copy(voiceReply = voiceReply)) },
            candidates = listOf(q.candidate), quotes = listOf(q), drive = DriveState.PARKED,
            alert = com.hmgcarapp.fuelwise.domain.AlertState(driving = false),
        )
        val armedWrites = mutableListOf<Boolean>()
        /** 경로 질의(plan)가 대기하는 동안을 만들기 위한 지연(r9R2-1). */
        var planDelayMs = 0L
        val runner: AlertRunner = AlertRunner(scope, speaker, listener, navi, RouteAdvisor({ if (planDelayMs > 0) delay(planDelayMs); mode }), { ui }, false, ::dispatch)
        fun dispatch(e: AlertEvent) {
            val (a, fx) = AlertFlow.reduce(ui.alert, e); ui = ui.copy(alert = a)
            fx.forEach { if (it is AlertEffect.SetArmed) armedWrites += it.armed else runner.run(it) }
        }
    }

    private fun TestScope.rig(speaker: FakeSpeaker = FakeSpeaker(), listener: FakeListener = FakeListener { ListenResult.Text("네") }, mode: RouteMode = RouteMode.NONE, voiceReply: Boolean = true) =
        Rig(backgroundScope, speaker, listener, mode = mode, voiceReply = voiceReply)

    @Test fun voiceAccept_runsSpeakListenNaviSpeakResult_thenBannerExpiresAt20s() = runTest {
        val r = rig()
        r.dispatch(AlertEvent.AlertRequested("잔량 15%"))
        advanceTimeBy(1); assertEquals(VoiceStatus.SPEAKING, r.ui.banner!!.voiceStatus)
        advanceTimeBy(1_000); assertEquals(VoiceStatus.ACCEPTED, r.ui.banner!!.voiceStatus)
        advanceTimeBy(1_000) // 결과 확인 발화 끝
        assertEquals(listOf("c" to NaviAction.REQUEST_ROUTE), r.navi.requests)
        assertEquals(2, r.speaker.spoken.size); assertEquals(NaviText.ROUTE_STARTED, r.speaker.spoken[1])
        assertNull("흐름 종료", r.ui.alert.inFlightId)
        assertEquals("배너는 TTL 전까지 유지", VoiceStatus.ACCEPTED, r.ui.banner!!.voiceStatus)
        advanceTimeBy(19_999); assertTrue(r.ui.banner != null)
        advanceTimeBy(2); assertNull(r.ui.banner)
        assertEquals("배너 소거 시 이 배너가 보낸 요청의 결과만 지움", NaviStatus.Idle, r.navi.status)
    }

    @Test fun noReply_marksBanner_andExpiresAfterTtl() = runTest {
        val r = rig(listener = FakeListener { delay(8_000); ListenResult.Timeout })
        r.dispatch(AlertEvent.AlertRequested("x"))
        advanceTimeBy(1_001); assertEquals(VoiceStatus.LISTENING, r.ui.banner!!.voiceStatus)
        advanceTimeBy(8_000); assertEquals(VoiceStatus.NO_REPLY, r.ui.banner!!.voiceStatus)
        advanceTimeBy(19_999); assertTrue(r.ui.banner != null)
        advanceTimeBy(2); assertNull(r.ui.banner)
    }

    @Test fun unknownRoute_yesIsNotAccepted_noNaviRequest() = runTest {
        val r = rig(mode = RouteMode.UNKNOWN)
        r.dispatch(AlertEvent.AlertRequested("x"))
        advanceTimeBy(2_000)
        assertEquals(VoiceStatus.NOT_ACCEPTED, r.ui.banner!!.voiceStatus)
        assertTrue(r.navi.requests.isEmpty())
    }

    @Test fun exceptionInSpeech_endsFailed_notStuckSpeaking_andExpires() = runTest {
        val r = rig(speaker = FakeSpeaker { throw IllegalStateException("tts") })
        r.dispatch(AlertEvent.AlertRequested("x"))
        advanceTimeBy(1)
        assertEquals(VoiceStatus.FAILED, r.ui.banner!!.voiceStatus)
        assertNull(r.ui.alert.inFlightId)
        advanceTimeBy(20_001); assertNull(r.ui.banner)
    }

    @Test fun exceptionInListener_endsFailed() = runTest {
        val r = rig(listener = FakeListener { throw IllegalStateException("stt") })
        r.dispatch(AlertEvent.AlertRequested("x"))
        advanceTimeBy(1_001)
        assertEquals(VoiceStatus.FAILED, r.ui.banner!!.voiceStatus)
        assertEquals(1, r.listener.calls)
    }

    @Test fun voiceReplyOff_speaksOnly_neverListens() = runTest {
        val r = rig(voiceReply = false)
        r.dispatch(AlertEvent.AlertRequested("x"))
        advanceTimeBy(1_001)
        assertEquals(VoiceStatus.VOICE_ONLY, r.ui.banner!!.voiceStatus)
        assertEquals(0, r.listener.calls)
    }

    @Test fun newAlert_cancelsOldFlow_stopsSpeech_andOldNeverFinishesOnNewBanner() = runTest {
        val r = rig(speaker = FakeSpeaker { delay(5_000); true })
        r.dispatch(AlertEvent.AlertRequested("a"))
        advanceTimeBy(100)
        val first = r.ui.banner!!.id
        val stopsBefore = r.speaker.stops
        r.dispatch(AlertEvent.AlertRequested("b"))
        assertTrue("교체 시 TTS 정지", r.speaker.stops > stopsBefore)
        advanceTimeBy(100)
        assertTrue(r.ui.banner!!.id > first)
        advanceTimeBy(5_000)
        assertEquals("이전 발화의 완료가 새 배너를 건드리지 않는다: 새 흐름만 진행", 1, r.listener.calls)
    }

    @Test fun dismissWhileSpeaking_cancelsFlow_noListen_andReleaseKeepsArmed() = runTest {
        val r = rig(speaker = FakeSpeaker { delay(5_000); true })
        r.dispatch(AlertEvent.AlertRequested("a")); advanceTimeBy(100)
        r.dispatch(AlertEvent.BannerDismissed)
        advanceTimeBy(10_000)
        assertNull(r.ui.banner); assertEquals(0, r.listener.calls); assertNull(r.ui.alert.inFlightId)
    }

    @Test fun releaseDuringSpeech_restoresArmed() = runTest {
        val r = rig(speaker = FakeSpeaker { delay(5_000); true })
        r.ui = r.ui.copy(alert = r.ui.alert.copy(armed = false))
        r.dispatch(AlertEvent.AlertRequested("a")); advanceTimeBy(100)
        r.dispatch(AlertEvent.AppReleased)
        assertEquals(listOf(true), r.armedWrites); assertTrue(r.ui.alert.armed)
    }

    @Test fun manualNaviRequest_fromEffect_updatesBannerMembership() = runTest {
        val r = rig(listener = FakeListener { ListenResult.Timeout })
        r.dispatch(AlertEvent.AlertRequested("x")); advanceTimeBy(1_100)
        r.dispatch(AlertEvent.ManualNavi("c", NaviAction.ADD_WAYPOINT)); advanceTimeBy(10)
        assertEquals(listOf("c" to NaviAction.ADD_WAYPOINT), r.navi.requests)
        assertEquals(1L, r.ui.alert.bannerReqId)
        r.dispatch(AlertEvent.BannerDismissed)
        assertEquals(NaviStatus.Idle, r.navi.status)
    }

    // ---- r8R2-1: 즉시 실행 디스패처(Unconfined)에서도 LAZY 시작이 슬롯 교체를 잃지 않는다 ----
    @Test fun unconfined_dismissDuringSpeech_cancelsTheSpeakJob_andNeverListens() = runTest(UnconfinedTestDispatcher()) {
        var cancelled = false; var rigRef: Rig? = null
        val sp = FakeSpeaker { rigRef!!.dispatch(AlertEvent.BannerDismissed); try { delay(5_000); true } catch (e: CancellationException) { cancelled = true; throw e } }
        val r = rig(speaker = sp); rigRef = r
        // 알림 계획 도착 직후(최상위 호출)부터 시작: Speak 효과가 즉시 실행되고, 발화 도중 닫힘이 같은 스택에서 일어난다
        r.ui = r.ui.copy(alert = r.ui.alert.copy(inFlightId = 1, seq = 1))
        r.dispatch(AlertEvent.AlertPlanned(1, "x", AlertFlowTest.NONE_PLAN, "주유소", r.q, true))
        advanceTimeBy(10_000)
        assertTrue("발화 job 이 슬롯에 들어간 뒤 시작돼 닫기에 취소된다", cancelled)
        assertEquals(0, r.listener.calls); assertNull(r.ui.banner); assertNull(r.ui.alert.inFlightId)
    }

    @Test fun unconfined_guide_keepsInnerGuidanceJobInSlot_soSecondPressIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.navi.awaitDelayMs = 5_000
        r.runner.guide(r.q.candidate)
        r.runner.guide(r.q.candidate) // 요청 결과 대기 중 중복 누름
        assertEquals(1, r.navi.requests.size)
        advanceTimeBy(6_000)
        r.runner.guide(r.q.candidate)
        assertEquals("끝난 뒤에는 다시 요청 가능", 2, r.navi.requests.size)
    }

    // ---- r9R2-1: plan 대기 중 주행이 시작되면 내비 요청·교체 확인을 내지 않는다(유일한 방어선) ----
    @Test fun guide_drivingStartsWhilePlanPending_sendsNeitherManualNaviNorReplaceAsked() = runTest {
        for (mode in listOf(RouteMode.NONE, RouteMode.UNKNOWN)) { // NONE -> ManualNavi 경로, UNKNOWN -> ReplaceAsked 경로
            val r = rig(mode = mode); r.planDelayMs = 1_000
            r.runner.guide(r.q.candidate)
            advanceTimeBy(500)
            r.ui = r.ui.copy(drive = DriveState.DRIVING) // plan 대기 중 주행 시작
            advanceTimeBy(2_000)
            assertTrue("$mode: 내비 요청 없음", r.navi.requests.isEmpty())
            assertNull("$mode: 교체 확인 없음", r.ui.alert.replaceConfirm)
        }
    }

    @Test fun guide_stillParkedAfterPlan_proceeds() = runTest {
        val r = rig(mode = RouteMode.NONE); r.planDelayMs = 1_000
        r.runner.guide(r.q.candidate)
        advanceTimeBy(2_000)
        assertEquals(1, r.navi.requests.size)
    }
}
