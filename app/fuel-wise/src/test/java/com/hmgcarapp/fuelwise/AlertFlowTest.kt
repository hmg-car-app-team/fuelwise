package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.domain.AlertEffect
import com.hmgcarapp.fuelwise.domain.AlertEvent
import com.hmgcarapp.fuelwise.domain.AlertFlow
import com.hmgcarapp.fuelwise.domain.AlertInput
import com.hmgcarapp.fuelwise.domain.AlertState
import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.BannerRules
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.Heard
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import com.hmgcarapp.fuelwise.navi.NaviAction
import com.hmgcarapp.fuelwise.navi.NaviReducer
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.NaviText
import com.hmgcarapp.fuelwise.navi.RouteMode
import com.hmgcarapp.fuelwise.navi.RoutePlan
import com.hmgcarapp.fuelwise.navi.RoutePlans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refactor-C: AlertFlow 이벤트 시퀀스 테스트(배선 회귀 방지). 컨트롤러가 하는 일 = 이 이벤트를 순서대로 넣는 것. */
class AlertFlowTest {
    private class H(driving: Boolean = false, armed: Boolean = true) {
        var s = AlertState(driving = driving, armed = armed)
        val all = mutableListOf<AlertEffect>()
        fun send(e: AlertEvent): List<AlertEffect> { val (n, fx) = AlertFlow.reduce(s, e); s = n; all += fx; return fx }
        /** 알림 시작 + 계획 도착(배너 표시·발화 요청까지). 반환 = 알림 번호. */
        fun fire(plan: RoutePlan = NONE_PLAN, reason: String = "시험 알림", best: Quote? = quote()): Long {
            send(AlertEvent.AlertRequested(reason))
            val id = s.inFlightId!!
            send(AlertEvent.AlertPlanned(id, reason, plan, "주유소", best, true))
            return id
        }
    }

    companion object {
        val NONE_PLAN = RoutePlans.decide(RouteMode.NONE, false, "주유소")
        val UNKNOWN_PLAN = RoutePlans.decide(RouteMode.UNKNOWN, false, "주유소")
        fun quote() = Quote(
            Candidate("c", "강남 센트럴 주유소", "X", EnergyKind.FUEL, LatLon(0.0, 0.0)), 1700.0, 1700.0, null, 40.0, 68000, 2.5, 0, 68000, 0.0, 0.0, 0, 68000, 0, 0, true,
        )
        fun low(level: Int? = 15, loaded: Boolean = true, best: Boolean = true) =
            AlertEvent.EnergyUpdated(AlertInput(level, null, 20, 50, loaded, best, listOf("c")))
        fun planFx(fx: List<AlertEffect>) = fx.filterIsInstance<AlertEffect.PlanAlert>()
    }

    // ---- (1) N1 ----
    @Test fun n1_alertHeldBeforeRealLocation_thenSpeaksAfterLocationReady() {
        val h = H()
        assertTrue("실위치 전 보류", h.send(low()).isEmpty())
        assertTrue("armed 소진 금지", h.s.armed)
        assertNull(h.s.inFlightId)
        h.send(AlertEvent.LocationReady)
        val fx = h.send(low())
        assertEquals(1, planFx(fx).size)
        assertTrue(fx.contains(AlertEffect.SetArmed(false)))
        assertFalse("armed 소진은 발화 시점", h.s.armed)
        h.send(AlertEvent.AlertPlanned(h.s.inFlightId!!, "잔량 15%", NONE_PLAN, "주유소", quote(), true))
        assertFalse(h.s.banner!!.message.contains("기본 위치"))
        assertTrue(h.all.any { it is AlertEffect.Speak })
    }

    @Test fun n1_after20sWithoutLocation_speaksWithUnverifiedLocationNote() {
        val h = H()
        assertTrue(h.send(low()).isEmpty())
        assertTrue(h.send(AlertEvent.OriginWaitElapsed).isEmpty())
        val fx = h.send(low())
        assertEquals(1, planFx(fx).size)
        h.send(AlertEvent.AlertPlanned(h.s.inFlightId!!, "잔량 15%", NONE_PLAN, "주유소", quote(), true))
        assertFalse("위치 미확인 문구는 더 이상 붙이지 않는다", h.s.banner!!.message.contains("기본 위치"))
        assertTrue("알림 자체는 나간다", h.s.banner != null)
        assertFalse(h.s.armed)
    }

    @Test fun n1_timeoutAfterLocationArrived_isIgnored_andLoadNotDoneStillHolds() {
        val h = H()
        h.send(AlertEvent.LocationReady)
        h.send(AlertEvent.OriginWaitElapsed)
        assertFalse(h.s.originWaitExpired)
        assertTrue("후보 로드 전에는 실위치가 있어도 보류", h.send(low(loaded = false)).isEmpty())
        assertTrue(h.s.armed)
    }

    // ---- (2) N2 ----
    @Test fun n2_replaceConfirmShownWhenParked_closesWhenDrivingStarts() {
        val h = H()
        h.send(AlertEvent.ReplaceAsked("c", "주유소"))
        assertEquals("c", h.s.replaceConfirm?.candidateId)
        val fx = h.send(AlertEvent.DriveChanged(true))
        assertNull(h.s.replaceConfirm)
        assertTrue("주행 진입 시 지난 내비 결과 소거", fx.contains(AlertEffect.ClearNaviFinished(null)))
        h.send(AlertEvent.ReplaceAsked("c", "주유소"))
        assertNull("주행 중에는 팝업을 띄우지 않는다", h.s.replaceConfirm)
        assertTrue("닫힌 팝업의 '바꾸기'는 무동작", h.send(AlertEvent.ReplaceConfirmed).isEmpty())
    }

    @Test fun n2_confirmReplace_startsRouteRequestAndClosesPopup() {
        val h = H()
        h.send(AlertEvent.ReplaceAsked("c", "주유소"))
        val fx = h.send(AlertEvent.ReplaceConfirmed)
        assertNull(h.s.replaceConfirm)
        assertEquals(listOf<AlertEffect>(AlertEffect.StartNavi("c", NaviAction.REQUEST_ROUTE, false, null)), fx)
        h.send(AlertEvent.ReplaceAsked("c", "주유소")); h.send(AlertEvent.ReplaceCancelled)
        assertNull(h.s.replaceConfirm)
    }

    // ---- (3) N3 ----
    @Test fun n3_previousNaviResultNeverAttachesToNewAlert() {
        val h = H()
        var navi: NaviStatus = NaviStatus.Idle
        fun apply(fx: List<AlertEffect>) = fx.filterIsInstance<AlertEffect.ClearNaviFinished>().forEach { navi = if (it.reqId == null) NaviReducer.settle(navi) else NaviReducer.settleFor(navi, it.reqId) }
        val id1 = h.fire(NONE_PLAN)
        h.send(AlertEvent.NaviRequested(5, fromBanner = true))
        assertEquals(5, h.s.bannerReqId)
        navi = NaviStatus.Success(NaviText.ROUTE_STARTED, 5) // 요청 5 의 결과
        apply(h.send(AlertEvent.AlertRequested("다음 알림"))) // 새 알림 시작
        assertEquals("새 알림 시작 때 낡은 결과 소거", NaviStatus.Idle, navi)
        assertEquals(NaviReducer.IGNORED, h.s.bannerReqId)
        assertTrue(h.all.contains(AlertEffect.ClearNaviFinished(null)))
        val id2 = h.s.inFlightId!!
        assertTrue(id2 > id1)
        h.send(AlertEvent.AlertPlanned(id2, "다음 알림", NONE_PLAN, "주유소", quote(), true))
        navi = NaviStatus.Success(NaviText.ROUTE_STARTED, 5) // 늦게 도착한 5번 결과가 남아 있어도
        assertTrue("이전 알림의 소거 타이머는 새 배너를 건드리지 못한다", h.send(AlertEvent.ExpiryElapsed(id1)).isEmpty())
        assertNotNullBanner(h)
        assertTrue("새 배너는 요청 소속이 없으므로 5번 결과를 지우지 않는다", h.send(AlertEvent.ExpiryElapsed(id2)).none { it is AlertEffect.ClearNaviFinished })
    }

    @Test fun n3_bannerExpiry_clearsOnlyItsOwnRequestResult() {
        val h = H()
        val id = h.fire(NONE_PLAN)
        h.send(AlertEvent.NaviRequested(7, fromBanner = true))
        h.send(AlertEvent.NaviRequested(9, fromBanner = false)) // 배너 소속이 아닌 요청
        h.send(AlertEvent.NaviRequested(NaviReducer.IGNORED, fromBanner = true)) // 무시된 요청
        assertEquals(7, h.s.bannerReqId)
        val fx = h.send(AlertEvent.ExpiryElapsed(id))
        assertNull(h.s.banner)
        assertEquals(listOf<AlertEffect>(AlertEffect.ClearNaviFinished(7)), fx)
        assertEquals(NaviReducer.IGNORED, h.s.bannerReqId)
    }

    private fun assertNotNullBanner(h: H) = assertTrue(h.s.banner != null)

    // ---- (4) N4 ----
    @Test fun n4_unknownRoute_askMentionsPhrase_andSingleSyllableIsNotAccepted() {
        val h = H()
        val id = h.fire(UNKNOWN_PLAN)
        assertTrue(h.s.banner!!.message.contains("바꿔줘"))
        assertTrue(h.s.banner!!.canOverwrite)
        h.send(AlertEvent.VoiceSpoken(id, true, true))
        assertEquals(VoiceStatus.LISTENING, h.s.banner!!.voiceStatus)
        val fx = h.send(AlertEvent.VoiceHeard(id, Heard.Text("네")))
        assertEquals(VoiceStatus.NOT_ACCEPTED, h.s.banner!!.voiceStatus)
        assertTrue("경로를 바꾸지 않는다", fx.none { it is AlertEffect.StartNavi })
        assertEquals(listOf<AlertEffect>(AlertEffect.ScheduleExpiry(id, BannerRules.TTL_MS)), fx)
        val h2 = H(); val id2 = h2.fire(UNKNOWN_PLAN); h2.send(AlertEvent.VoiceSpoken(id2, true, true))
        h2.send(AlertEvent.VoiceHeard(id2, Heard.Text("응")))
        assertEquals(VoiceStatus.NOT_ACCEPTED, h2.s.banner!!.voiceStatus)
    }

    @Test fun n4_unknownRoute_explicitPhrase_acceptsAndStartsNavi_thenSpeaksResult() {
        val h = H()
        val id = h.fire(UNKNOWN_PLAN)
        h.send(AlertEvent.VoiceSpoken(id, true, true))
        val fx = h.send(AlertEvent.VoiceHeard(id, Heard.Text("바꿔줘")))
        assertEquals(VoiceStatus.ACCEPTED, h.s.banner!!.voiceStatus)
        assertEquals(listOf<AlertEffect>(AlertEffect.StartNavi("c", NaviAction.REQUEST_ROUTE, true, id)), fx)
        assertEquals("결과 확인 발화까지 흐름 유지", id, h.s.inFlightId)
        h.send(AlertEvent.NaviRequested(3, true))
        val fx2 = h.send(AlertEvent.NaviResult(id, NaviStatus.Success(NaviText.ROUTE_STARTED, 3)))
        assertEquals(listOf<AlertEffect>(AlertEffect.SpeakResult(id, NaviText.ROUTE_STARTED)), fx2)
        val fx3 = h.send(AlertEvent.ResultSpoken(id))
        assertEquals(listOf<AlertEffect>(AlertEffect.ScheduleExpiry(id, 20_000L)), fx3)
        assertNull(h.s.inFlightId)
    }

    @Test fun n4_knownRoute_singleSyllableAccepts_usingActionDecidedAtAlertTime() {
        val h = H()
        val id = h.fire(RoutePlans.decide(RouteMode.ACTIVE, false, "주유소"))
        h.send(AlertEvent.VoiceSpoken(id, true, true))
        val fx = h.send(AlertEvent.VoiceHeard(id, Heard.Text("네")))
        assertEquals(listOf<AlertEffect>(AlertEffect.StartNavi("c", NaviAction.ADD_WAYPOINT, true, id)), fx)
    }

    @Test fun n4_acceptWithNoNaviResult_speaksNoResponse() {
        val h = H()
        val id = h.fire(NONE_PLAN); h.send(AlertEvent.VoiceSpoken(id, true, true)); h.send(AlertEvent.VoiceHeard(id, Heard.Text("응")))
        assertEquals(listOf<AlertEffect>(AlertEffect.SpeakResult(id, NaviText.NO_RESPONSE)), h.send(AlertEvent.NaviResult(id, null)))
    }

    // ---- (5) armed ----
    @Test fun armed_noDuplicateAlert_untilRecovered_thenRearms() {
        val h = H(); h.send(AlertEvent.LocationReady)
        assertEquals(1, planFx(h.send(low())).size)
        assertTrue("울린 뒤에는 같은 상태에서 재알림 없음", planFx(h.send(low())).isEmpty())
        assertTrue(planFx(h.send(low(25))).isEmpty()) // 20% 초과 ~ 재무장 기준(30%) 미만
        assertFalse(h.s.armed)
        val id = h.s.inFlightId!!; h.send(AlertEvent.AlertPlanned(id, "x", NONE_PLAN, "주유소", quote(), true))
        val fx = h.send(low(40)) // 회복
        assertTrue(h.s.armed)
        assertTrue(fx.contains(AlertEffect.SetArmed(true)))
        assertTrue("재무장 시 지난 배너·음성 흐름 소거", fx.contains(AlertEffect.CancelVoice))
        assertNull(h.s.banner); assertNull(h.s.inFlightId)
        assertEquals("다시 낮아지면 재알림", 1, planFx(h.send(low())).size)
    }

    @Test fun armed_noSignal_keepsState_andNoCandidate_firesOnceWithoutListening() {
        val h = H(armed = false); h.send(AlertEvent.LocationReady)
        assertTrue(h.send(low(level = null)).isEmpty()); assertFalse(h.s.armed)
        val h2 = H(); h2.send(AlertEvent.LocationReady)
        val fx = h2.send(low(best = false))
        assertEquals("잔량 부족", planFx(fx).single().reason)
        val id = h2.s.inFlightId!!
        h2.send(AlertEvent.AlertPlanned(id, "잔량 부족", NONE_PLAN, "주유소", null, true))
        val sp = h2.send(AlertEvent.VoiceSpoken(id, true, true))
        assertEquals("후보 없음: 청취 없음", VoiceStatus.VOICE_ONLY, h2.s.banner!!.voiceStatus)
        assertEquals(listOf<AlertEffect>(AlertEffect.ScheduleExpiry(id, 20_000L)), sp)
    }

    @Test fun release_duringAlertFlow_restoresArmed_butNotAfterItEnded() {
        val h = H(); h.send(AlertEvent.LocationReady); h.send(low())
        assertFalse(h.s.armed)
        assertTrue("배너가 뜨기 전 경로 확인 중(inFlight)", h.send(AlertEvent.AppReleased).contains(AlertEffect.SetArmed(true)))
        assertTrue(h.s.armed)

        val h2 = H(armed = false); val id = h2.fire()
        h2.send(AlertEvent.VoiceSpoken(id, true, true)) // LISTENING
        assertTrue(h2.send(AlertEvent.AppReleased).contains(AlertEffect.SetArmed(true)))

        val h3 = H(armed = false); val id3 = h3.fire(); h3.send(AlertEvent.VoiceSpoken(id3, true, true)); h3.send(AlertEvent.VoiceHeard(id3, Heard.Text("네")))
        assertTrue("수락 후 결과 확인 발화 중에도 복구", h3.send(AlertEvent.AppReleased).contains(AlertEffect.SetArmed(true)))

        val h4 = H(armed = false); val id4 = h4.fire(); h4.send(AlertEvent.VoiceSpoken(id4, true, true)); h4.send(AlertEvent.VoiceHeard(id4, Heard.Timeout))
        assertTrue("흐름이 끝난 뒤에는 그대로", h4.send(AlertEvent.AppReleased).isEmpty())
        assertFalse(h4.s.armed)
    }

    // ---- (6) 주행 중 억제 ----
    @Test fun driving_suppressesUserDismissAndToast_expiryStillClears_deferredShownWhenParked() {
        val h = H(driving = true)
        val id = h.fire()
        h.send(AlertEvent.VoiceSpoken(id, true, true)); h.send(AlertEvent.VoiceHeard(id, Heard.Timeout))
        assertTrue("주행 중 사용자 닫기 억제", h.send(AlertEvent.BannerDismissed).isEmpty())
        assertEquals(id, h.s.banner?.id)
        h.send(AlertEvent.Notify("기록됨")); assertNull("주행 중 토스트 세팅 억제", h.s.message)
        h.send(AlertEvent.NotifyOrDefer("삭제하지 못했어요")); assertNull(h.s.message); assertEquals("삭제하지 못했어요", h.s.deferredMessage)
        h.send(AlertEvent.DriveChanged(false))
        assertEquals("정차 시 보관분 한 번 표시", "삭제하지 못했어요", h.s.message); assertNull(h.s.deferredMessage)
        h.send(AlertEvent.MessageConsumed); assertNull(h.s.message)
        h.send(AlertEvent.DriveChanged(true))
        // 시스템 소거(타이머)는 주행 중에도 동작: 소거 예약은 음성 흐름 종료 시 이미 걸려 있다
        assertTrue(h.all.contains(AlertEffect.ScheduleExpiry(id, 20_000L)))
        h.send(AlertEvent.ExpiryElapsed(id)); assertNull(h.s.banner)
    }

    @Test fun parked_userDismiss_cancelsVoice_clearsBanner_andOwnNaviResult() {
        val h = H(); val id = h.fire(); h.send(AlertEvent.NaviRequested(4, true))
        val fx = h.send(AlertEvent.BannerDismissed)
        assertNull(h.s.banner); assertNull("흐름 취소로 inFlight 해제", h.s.inFlightId)
        assertEquals(listOf<AlertEffect>(AlertEffect.CancelVoice, AlertEffect.ClearNaviFinished(4)), fx)
        assertTrue("취소된 흐름의 늦은 이벤트는 무시", h.send(AlertEvent.VoiceSpoken(id, true, true)).isEmpty())
        assertTrue(h.send(AlertEvent.VoiceHeard(id, Heard.Text("네"))).isEmpty())
    }

    // r9R3-1: 복원 안내(NotifyOrDefer)는 앱 시작 직후(주행 기본값)에 도착해도 정차 시 정확히 한 번 표시된다
    @Test fun restoredNotice_arrivingWhileDriving_showsOnceOnParking_notAgainAfterRedrive() {
        val h = H(driving = true)
        h.send(AlertEvent.NotifyOrDefer("저장된 데이터가 손상돼 처음 상태로 시작했어요"))
        assertNull(h.s.message)
        h.send(AlertEvent.DriveChanged(false))
        assertEquals("저장된 데이터가 손상돼 처음 상태로 시작했어요", h.s.message)
        h.send(AlertEvent.MessageConsumed)
        h.send(AlertEvent.DriveChanged(true)); h.send(AlertEvent.DriveChanged(false))
        assertNull("다시 주행·정차해도 재표시 없음", h.s.message); assertNull(h.s.deferredMessage)
    }

    @Test fun toast_whileParked_isSet_andRestoredNoticeFollowsDrivingRule() {
        val h = H(); h.send(AlertEvent.Notify("a")); assertEquals("a", h.s.message)
        h.send(AlertEvent.DriveChanged(true)); assertNull("주행 진입 시 토스트 폐기", h.s.message)
        h.send(AlertEvent.NotifyOrDefer("b")); h.send(AlertEvent.DeferredDropped); h.send(AlertEvent.DriveChanged(false))
        assertNull("보관분이 버려졌으면 표시 없음", h.s.message)
    }

    // ---- (7) 종료 상태마다 소거 예약 ----
    @Test fun everyVoiceEnding_schedulesBannerExpiry() {
        fun ended(heard: Heard, voiceReply: Boolean = true): Pair<String, List<AlertEffect>> {
            val h = H(); val id = h.fire(); val sp = h.send(AlertEvent.VoiceSpoken(id, true, voiceReply))
            val fx = if (sp.any { it is AlertEffect.Listen }) h.send(AlertEvent.VoiceHeard(id, heard)) else sp
            assertNull("흐름 종료 후 inFlight 없음", h.s.inFlightId)
            return h.s.banner!!.voiceStatus to fx
        }
        val expiry = { id: Long -> AlertEffect.ScheduleExpiry(id, 20_000L) }
        assertEquals(VoiceStatus.NO_REPLY to listOf(expiry(1)), ended(Heard.Timeout))
        assertEquals(VoiceStatus.DECLINED to listOf(expiry(1)), ended(Heard.Text("싫어")))
        assertEquals(VoiceStatus.DECLINED to listOf(expiry(1)), ended(Heard.Text("그냥 아무말이나 길게 해볼게요")))
        assertEquals(VoiceStatus.NO_REPLY to listOf(expiry(1)), ended(Heard.Text("  ")))
        assertEquals(VoiceStatus.FAILED to listOf(expiry(1)), ended(Heard.Failure))
        assertEquals("음성 응답 끔", VoiceStatus.VOICE_ONLY to listOf(expiry(1)), ended(Heard.Timeout, voiceReply = false))
    }

    @Test fun listenEffect_carriesTtsResult_forLogOnly() {
        val h = H(); val id = h.fire()
        assertEquals(listOf<AlertEffect>(AlertEffect.Listen(id, false)), h.send(AlertEvent.VoiceSpoken(id, spoke = false, voiceReply = true)))
    }

    // ---- (8) 예외로 끝나도 고착 없음 ----
    @Test fun exception_afterBannerShown_endsAsFailed_withExpiry_notStuckSpeaking() {
        val h = H(); val id = h.fire()
        assertEquals(VoiceStatus.SPEAKING, h.s.banner!!.voiceStatus)
        val fx = h.send(AlertEvent.AlertFailed(id))
        assertEquals(VoiceStatus.FAILED, h.s.banner!!.voiceStatus)
        assertEquals(listOf<AlertEffect>(AlertEffect.ScheduleExpiry(id, 20_000L)), fx)
        assertNull(h.s.inFlightId)
        assertFalse("release 시 armed 복구 대상 아님", h.send(AlertEvent.AppReleased).any { it is AlertEffect.SetArmed })
    }

    @Test fun exception_beforeBanner_schedulesExpiry_andStaleFailureIsIgnored() {
        val h = H(); h.send(AlertEvent.AlertRequested("x")); val id = h.s.inFlightId!!
        assertEquals(listOf<AlertEffect>(AlertEffect.ScheduleExpiry(id, 20_000L)), h.send(AlertEvent.AlertFailed(id)))
        assertNull(h.s.banner); assertNull(h.s.inFlightId)
        val h2 = H(); h2.send(AlertEvent.AlertRequested("a")); val old = h2.s.inFlightId!!; h2.send(AlertEvent.AlertRequested("b"))
        assertTrue("교체된 이전 알림의 실패는 새 알림에 영향 없음", h2.send(AlertEvent.AlertFailed(old)).isEmpty())
        assertEquals(old + 1, h2.s.inFlightId)
    }

    @Test fun exceptionDuringNaviResult_afterAccept_endsAsFailed() {
        val h = H(); val id = h.fire(); h.send(AlertEvent.VoiceSpoken(id, true, true)); h.send(AlertEvent.VoiceHeard(id, Heard.Text("네")))
        h.send(AlertEvent.AlertFailed(id))
        assertEquals(VoiceStatus.FAILED, h.s.banner!!.voiceStatus)
    }

    // ---- 기타 불변식 ----
    @Test fun newAlert_replacesOldFlow_andOldEventsAreIgnored() {
        val h = H(); val old = h.fire()
        val fx = h.send(AlertEvent.AlertRequested("새"))
        assertEquals(AlertEffect.CancelVoice, fx[0]); assertEquals(AlertEffect.ClearNaviFinished(null), fx[1])
        assertTrue(h.send(AlertEvent.VoiceSpoken(old, true, true)).isEmpty())
        assertTrue(h.send(AlertEvent.AlertPlanned(old, "x", NONE_PLAN, "주유소", quote(), true)).isEmpty())
    }

    @Test fun energyUpdate_dropsBannerCandidateThatDisappeared() {
        val h = H(); h.fire()
        assertEquals("c", h.s.banner!!.candidateId)
        h.send(AlertEvent.EnergyUpdated(AlertInput(80, null, 20, 50, true, true, listOf("other"))))
        assertNull(h.s.banner!!.candidateId)
    }

    @Test fun manualNavi_andAcceptBanner_requestWithBannerMembership() {
        val h = H(); h.fire()
        assertEquals(listOf<AlertEffect>(AlertEffect.StartNavi("c", NaviAction.ADD_WAYPOINT, true, null)), h.send(AlertEvent.ManualNavi("c", NaviAction.ADD_WAYPOINT)))
        assertEquals(listOf<AlertEffect>(AlertEffect.StartNavi("zzz", NaviAction.REQUEST_ROUTE, false, null)), h.send(AlertEvent.ManualNavi("zzz", NaviAction.REQUEST_ROUTE)))
        assertEquals(listOf<AlertEffect>(AlertEffect.StartNavi("c", NaviAction.REQUEST_ROUTE, true, null)), h.send(AlertEvent.AcceptBanner))
        assertTrue(H().send(AlertEvent.AcceptBanner).single() is AlertEffect.Warn)
    }

    @Test fun rearmEvent_setsArmed_clearsBanner() {
        val h = H(armed = false); h.fire()
        val fx = h.send(AlertEvent.Rearm)
        assertTrue(h.s.armed); assertNull(h.s.banner); assertNull(h.s.inFlightId)
        assertEquals(AlertEffect.SetArmed(true), fx.first()); assertTrue(fx.contains(AlertEffect.CancelVoice))
    }

    @Test fun restored_setsArmedFromSavedState() {
        val h = H(); h.send(AlertEvent.Restored(false)); assertFalse(h.s.armed)
        h.send(AlertEvent.LocationReady)
        assertTrue("저장된 armed=false 면 낮은 잔량에서도 재알림 없음", planFx(h.send(low())).isEmpty())
    }

    // ---- r8R2-3: 배너가 떠 있을 때 덮어쓰기 확인 -> 배너 소속 요청 ----
    @Test fun replaceConfirmed_withBannerOfSameCandidate_isFromBanner_andDismissClearsOwnResult() {
        val h = H(); h.fire()
        h.send(AlertEvent.ReplaceAsked("c", "주유소"))
        val fx = h.send(AlertEvent.ReplaceConfirmed)
        assertEquals(listOf<AlertEffect>(AlertEffect.StartNavi("c", NaviAction.REQUEST_ROUTE, true, null)), fx)
        h.send(AlertEvent.NaviRequested(8, fromBanner = true))
        assertEquals(8L, h.s.bannerReqId)
        assertEquals(listOf<AlertEffect>(AlertEffect.CancelVoice, AlertEffect.ClearNaviFinished(8)), h.send(AlertEvent.BannerDismissed))
        assertEquals(NaviReducer.IGNORED, h.s.bannerReqId)
    }

    @Test fun staleAlertId_naviResult_hasNoEffect() {
        val h = H(); val old = h.fire()
        h.send(AlertEvent.AlertRequested("새 알림")) // 이전 알림 번호는 더 이상 진행 중이 아니다
        val before = h.s
        assertTrue(h.send(AlertEvent.NaviResult(old, NaviStatus.Success(NaviText.ROUTE_STARTED, 1))).isEmpty())
        assertEquals(before, h.s)
    }
}
