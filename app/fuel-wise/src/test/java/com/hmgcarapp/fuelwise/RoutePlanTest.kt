package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.navi.GuidanceStep
import com.hmgcarapp.fuelwise.navi.NaviAction
import com.hmgcarapp.fuelwise.navi.NaviReducer
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.NaviText
import com.hmgcarapp.fuelwise.navi.RouteAdvisor
import com.hmgcarapp.fuelwise.navi.RouteMode
import com.hmgcarapp.fuelwise.navi.RoutePlans
import com.hmgcarapp.fuelwise.navi.RouteStateProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K6: 활성 경로 상태별 분기·문구(가짜 RouteStateProvider). 에뮬레이터는 내비 엔진이 오프라인이라 실제 경로 성공은 검증하지 못한다 -
 * 여기서는 질의 -> 분기 -> 동작 종류 -> 문구까지만 검증한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlanTest {
    private fun advisor(p: RouteStateProvider) = RouteAdvisor(p, 2000L)

    @Test fun activeRoute_addsWaypoint_andAsksVia() = runTest {
        val plan = advisor { RouteMode.ACTIVE }.plan(false, "주유소")
        assertEquals(NaviAction.ADD_WAYPOINT, plan.action); assertEquals("경유지로 추가할까요?", plan.ask)
    }

    @Test fun noRoute_requestsRoute_andAsksStart() = runTest {
        val plan = advisor { RouteMode.NONE }.plan(false, "충전소")
        assertEquals(NaviAction.REQUEST_ROUTE, plan.action); assertEquals("안내를 시작할까요?", plan.ask)
    }

    @Test fun unknown_warnsItMayReplace_andRequestsRoute() = runTest {
        val plan = advisor { RouteMode.UNKNOWN }.plan(false, "충전소")
        assertEquals(NaviAction.REQUEST_ROUTE, plan.action)
        assertEquals("현재 경로를 바꿀까요? 바꾸려면 '바꿔줘'라고 말씀해 주세요", plan.ask)
        assertTrue(RoutePlans.askReplace("주유소").contains("바꿀까요"))
    }

    @Test fun noResponse_within2s_isUnknown() = runTest {
        val plan = advisor { delay(10_000); RouteMode.ACTIVE }.plan(false, "주유소")
        assertEquals(RouteMode.UNKNOWN, plan.mode)
        assertEquals(2000L, testScheduler.currentTime) // 2초 상한
    }

    @Test fun providerException_isUnknown() = runTest {
        assertEquals(RouteMode.UNKNOWN, advisor { throw IllegalStateException("sdk") }.plan(false, "주유소").mode)
    }

    @Test fun demoDestination_usesRouteWithWaypoint_withoutQuery() = runTest {
        var asked = false
        val plan = advisor { asked = true; RouteMode.NONE }.plan(true, "주유소")
        assertEquals(NaviAction.ROUTE_WITH_WAYPOINT, plan.action); assertFalse(asked)
    }

    @Test fun buttonLabels_nameTheTarget() {
        assertEquals("이 충전소로 안내 시작", RoutePlans.buttonLabel("충전소", false))
        assertEquals("이 주유소로 안내 시작", RoutePlans.buttonLabel("주유소", false))
        assertEquals("이 주유소를 경유지로 추가", RoutePlans.buttonLabel("주유소", true))
    }

    // ---- K13: 요청 번호·겹침 ----
    @Test fun oldTimeout_doesNotFailNewPending() {
        val newer = NaviStatus.Pending("새 요청", 2)
        assertSame(newer, NaviReducer.onTimeout(newer, 1))
        assertEquals(NaviStatus.Failed(NaviText.NO_RESPONSE, 2), NaviReducer.onTimeout(newer, 2))
    }

    @Test fun newRequestRejectedWhilePending() {
        assertFalse(NaviReducer.canStart(NaviStatus.Pending("x", 1)))
        assertTrue(NaviReducer.canStart(NaviStatus.Idle)); assertTrue(NaviReducer.canStart(NaviStatus.Failed("x")))
    }

    @Test fun spokenResult_isShortKorean() {
        assertEquals(NaviText.WAYPOINT_ADDED, NaviText.spoken(NaviStatus.Success(NaviText.WAYPOINT_ADDED)))
        assertEquals("경로를 찾지 못했어요", NaviText.spoken(NaviStatus.Failed("경로를 찾지 못했어요")))
        assertEquals("", NaviText.spoken(null)) // 무응답: 실패 음성 없음
        assertEquals("", NaviText.spoken(NaviReducer.onTimeout(NaviStatus.Pending("x", 1), 1)))
        assertTrue(NaviText.isNoResponse(null)); assertTrue(NaviText.isNoResponse(NaviReducer.onTimeout(NaviStatus.Pending("x", 1), 1)))
        val err = NaviReducer.onError(NaviStatus.Pending("x", 1), "CAUSE_SEARCH_ROUTE")
        assertFalse(NaviText.isNoResponse(err)); assertEquals("요청에 실패했어요", NaviText.spoken(err))
        // 배지: 무응답은 '요청 실패'가 아니라 중립 단어
        assertEquals("결과 미확인", com.hmgcarapp.fuelwise.navi.NaviConnectRules.shortWord(NaviStatus.Failed(NaviText.NO_RESPONSE, 1)))
        assertEquals("요청 실패", com.hmgcarapp.fuelwise.navi.NaviConnectRules.shortWord(err))
        assertFalse(com.hmgcarapp.fuelwise.ui.drivingHasContent(false, ""))
        assertTrue(com.hmgcarapp.fuelwise.ui.drivingHasContent(false, "주행가능거리 123km"))
        assertTrue(com.hmgcarapp.fuelwise.ui.drivingHasContent(true, ""))
    }

    // ---- M2: 배너 라벨·수동 누름 재확인·팝업 조건 ----
    @Test fun bannerButtonLabel_followsActionAndMode() {
        assertEquals("경유지로 추가", RoutePlans.bannerButtonLabel(NaviAction.ADD_WAYPOINT, RouteMode.ACTIVE, "주유소"))
        assertEquals("이 충전소로 안내 시작", RoutePlans.bannerButtonLabel(NaviAction.REQUEST_ROUTE, RouteMode.NONE, "충전소"))
        assertEquals("이 주유소로 안내 시작", RoutePlans.bannerButtonLabel(NaviAction.REQUEST_ROUTE, RouteMode.NONE, "주유소"))
        assertEquals("현재 경로를 바꾸기", RoutePlans.bannerButtonLabel(NaviAction.REQUEST_ROUTE, RouteMode.UNKNOWN, "주유소"))
        assertEquals("이 주유소를 경유지로 추가", RoutePlans.bannerButtonLabel(NaviAction.ROUTE_WITH_WAYPOINT, RouteMode.UNKNOWN, "주유소"))
    }

    @Test fun manualPress_rechecksRouteState_insteadOfReusingStaleAction() = runTest {
        var mode = RouteMode.ACTIVE; var queries = 0
        val adv = advisor { queries++; mode }
        val atAlert = adv.plan(false, "주유소")
        assertEquals(NaviAction.ADD_WAYPOINT, atAlert.action)
        mode = RouteMode.NONE // 20초 사이에 경로 안내가 끝남
        val manual = RoutePlans.manualStep(adv.plan(false, "주유소"))
        assertEquals("경유지 추가가 아니라 새 안내", GuidanceStep.Execute(NaviAction.REQUEST_ROUTE), manual)
        assertEquals("눌릴 때마다 다시 질의", 2, queries)
        mode = RouteMode.ACTIVE // 반대로 알림 뒤에 경로가 생김
        assertEquals(GuidanceStep.Execute(NaviAction.ADD_WAYPOINT), RoutePlans.manualStep(adv.plan(false, "충전소")))
    }

    @Test fun manualPress_withUnknownRoute_asksConfirmationEvenFromBanner() = runTest {
        assertEquals(GuidanceStep.ConfirmReplace, RoutePlans.manualStep(advisor { RouteMode.UNKNOWN }.plan(false, "주유소")))
        assertEquals("무응답(2초)도 UNKNOWN 이므로 팝업", GuidanceStep.ConfirmReplace, RoutePlans.manualStep(advisor { delay(10_000); RouteMode.ACTIVE }.plan(false, "주유소")))
        assertEquals(GuidanceStep.ConfirmReplace, RoutePlans.manualStep(advisor { throw IllegalStateException("x") }.plan(false, "주유소")))
        assertEquals(GuidanceStep.Execute(NaviAction.REQUEST_ROUTE), RoutePlans.manualStep(advisor { RouteMode.NONE }.plan(false, "주유소")))
    }

    @Test fun manualPress_withDemoDestination_neverNeedsReplaceConfirm() = runTest {
        assertEquals(GuidanceStep.Execute(NaviAction.ROUTE_WITH_WAYPOINT), RoutePlans.manualStep(advisor { RouteMode.UNKNOWN }.plan(true, "주유소")))
    }

    @Test fun canOverwrite_onlyForUnknownDirectRoute() = runTest {
        assertTrue(advisor { RouteMode.UNKNOWN }.plan(false, "주유소").canOverwrite)
        assertFalse(advisor { RouteMode.NONE }.plan(false, "주유소").canOverwrite)
        assertFalse(advisor { RouteMode.ACTIVE }.plan(false, "주유소").canOverwrite)
        assertFalse(advisor { RouteMode.UNKNOWN }.plan(true, "주유소").canOverwrite)
    }

    // ---- C3: 요청 번호 ----
    @Test fun result_isBoundToItsOwnRequest() {
        val prev = NaviStatus.Success(NaviText.WAYPOINT_ADDED, 1)
        assertEquals("이전 요청의 결과는 다음 요청의 결과가 아니다", null, NaviReducer.resultFor(prev, 2))
        assertEquals(prev, NaviReducer.resultFor(prev, 1))
        assertEquals(null, NaviReducer.resultFor(NaviStatus.Pending("x", 2), 2))
        assertEquals(null, NaviReducer.resultFor(NaviStatus.Idle, 2))
    }

    @Test fun ignoredRequest_getsNoResult() {
        assertEquals("무시된 요청(0)은 어떤 결과에도 귀속되지 않는다", null, NaviReducer.resultFor(NaviStatus.Success("ok", 0), NaviReducer.IGNORED))
        assertEquals(null, NaviReducer.resultFor(NaviStatus.Failed("x", 0), NaviReducer.IGNORED))
    }

    @Test fun reducer_carriesRequestIdToResults() {
        val p = NaviStatus.Pending("경유지 추가 요청 중", 7)
        assertEquals(NaviStatus.Success(NaviText.WAYPOINT_ADDED, 7), NaviReducer.onSuccess(p, NaviText.WAYPOINT_ADDED))
        assertEquals(NaviStatus.Failed("요청에 실패했어요", 7), NaviReducer.onError(p, "CAUSE_SEARCH_ROUTE"))
        assertEquals(NaviStatus.Failed(NaviText.NO_RESPONSE, 7), NaviReducer.onTimeout(p, 7))
    }

    @Test fun sequencer_isMonotonicFrom1_andNeverIgnored() {
        val q = NaviReducer.Sequencer()
        val ids = List(5) { q.next() }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids)
        assertTrue(ids.none { it == NaviReducer.IGNORED })
    }
}
