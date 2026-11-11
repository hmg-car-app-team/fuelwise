package com.hmgcarapp.fuelwise.navi

import kotlinx.coroutines.withTimeoutOrNull

/** 내비에 활성 안내 경로가 있는지. UNKNOWN = 확인 실패/무응답(덮어쓸 수 있다고 보고 사용자에게 알린다). */
enum class RouteMode { ACTIVE, NONE, UNKNOWN }

/** 후보를 내비에 넘기는 방법. */
enum class NaviAction {
    /** 진행 중인 경로가 있다 -> 경유지로 추가(addWaypoint). */
    ADD_WAYPOINT,
    /** 경로가 없거나 확인 불가 -> 후보를 목적지로 직접 안내(requestRoute). */
    REQUEST_ROUTE,
    /** 시연 목적지가 설정된 경우만: 목적지 + 후보 경유지(requestRouteWithWaypoints). */
    ROUTE_WITH_WAYPOINT,
}

/** 활성 경로 상태 질의(내비 SDK 콜백을 감싼다). 테스트에서는 가짜 구현을 쓴다. */
fun interface RouteStateProvider {
    suspend fun routeMode(): RouteMode
}

/** 활성 경로의 실제 목적지 질의. 미연결·미지원·무응답·실패는 null(호출한 쪽은 목적지 없음으로 폴백). */
fun interface DestinationProvider {
    suspend fun routeDestination(): com.hmgcarapp.fuelwise.domain.RouteDestination?
}

/**
 * 활성 경로의 목적지를 주기적으로 갱신하는 판단(순수; 호출 시점은 앱의 기존 위치/재시도 루프가 정한다).
 * [minIntervalMs] 안에는 다시 묻지 않고, 활성 경로가 아니거나 질의가 실패하면 null(= 현행 왕복 근사).
 */
class RouteDestinationPoller(
    private val modes: RouteStateProvider,
    private val dests: DestinationProvider,
    private val clock: () -> Long,
    private val minIntervalMs: Long = 30_000L,
    private val timeoutMs: Long = 2500L,
) {
    private var lastAt: Long? = null

    fun due(): Boolean { val t = lastAt ?: return true; val dt = clock() - t; return dt < 0 || dt >= minIntervalMs }

    /** 한 번 질의한다. 예외·시간 초과·잘못된 좌표는 모두 null. */
    suspend fun poll(): com.hmgcarapp.fuelwise.domain.RouteDestination? {
        lastAt = clock()
        val mode = try { withTimeoutOrNull(timeoutMs) { modes.routeMode() } ?: RouteMode.UNKNOWN }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { RouteMode.UNKNOWN }
        if (mode != RouteMode.ACTIVE) return null
        val d = try { withTimeoutOrNull(timeoutMs) { dests.routeDestination() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { null }
        return d?.takeIf { it.pos.lat in -90.0..90.0 && it.pos.lon in -180.0..180.0 && !(it.pos.lat == 0.0 && it.pos.lon == 0.0) }
    }
}

data class RoutePlan(val mode: RouteMode, val action: NaviAction, val ask: String) {
    /** 확인 불가(UNKNOWN)라 기존 경로를 덮어쓸 수 있는 요청인지(단음절 음성 수락 금지·수동 확인 팝업 대상). */
    val canOverwrite: Boolean get() = mode == RouteMode.UNKNOWN && action == NaviAction.REQUEST_ROUTE
}

/** 수동 누름(배너·상세 버튼)의 다음 단계: 바로 실행하거나 덮어쓰기 확인 팝업. */
sealed interface GuidanceStep {
    data class Execute(val action: NaviAction) : GuidanceStep
    data object ConfirmReplace : GuidanceStep
}

/** 경로 상태 -> 동작·문구 결정(순수). */
object RoutePlans {
    const val ASK_VIA = "경유지로 추가할까요?"
    const val ASK_START = "안내를 시작할까요?"

    /** [noun] 은 "주유소"/"충전소". */
    fun askReplace(@Suppress("UNUSED_PARAMETER") noun: String) = "현재 경로를 바꿀까요? 바꾸려면 '바꿔줘'라고 말씀해 주세요"

    fun decide(mode: RouteMode, hasDestination: Boolean, noun: String): RoutePlan = when {
        hasDestination -> RoutePlan(mode, NaviAction.ROUTE_WITH_WAYPOINT, ASK_VIA) // 시연 목적지(경로를 새로 만든다)
        mode == RouteMode.ACTIVE -> RoutePlan(mode, NaviAction.ADD_WAYPOINT, ASK_VIA)
        mode == RouteMode.NONE -> RoutePlan(mode, NaviAction.REQUEST_ROUTE, ASK_START)
        else -> RoutePlan(mode, NaviAction.REQUEST_ROUTE, askReplace(noun))
    }

    /** 안내 시작 버튼 문구(대상 포함). */
    fun buttonLabel(noun: String, hasDestination: Boolean) =
        if (hasDestination) "이 ${noun}를 경유지로 추가" else "이 ${noun}로 안내 시작"

    /** 배너 버튼 라벨(알림 때 확인한 [action]/[mode] 기준). */
    fun bannerButtonLabel(action: NaviAction, mode: RouteMode, noun: String): String = when {
        action == NaviAction.ADD_WAYPOINT -> "경유지로 추가"
        action == NaviAction.ROUTE_WITH_WAYPOINT -> "이 ${noun}를 경유지로 추가"
        mode == RouteMode.UNKNOWN -> "현재 경로를 바꾸기"
        else -> "이 ${noun}로 안내 시작"
    }

    /** 수동 누름: 실행 직전에 다시 확인한 [plan] 으로 다음 단계를 정한다(알림 시점의 낡은 action 을 쓰지 않는다). */
    fun manualStep(plan: RoutePlan): GuidanceStep = if (plan.canOverwrite) GuidanceStep.ConfirmReplace else GuidanceStep.Execute(plan.action)

    fun nounOf(isEv: Boolean) = if (isEv) "충전소" else "주유소"
}

/** 알림 문구를 만들기 전에 활성 경로를 확인한다. 실패·예외·시간 초과는 모두 UNKNOWN. */
class RouteAdvisor(private val provider: RouteStateProvider, private val timeoutMs: Long = 2000L) {
    suspend fun plan(hasDestination: Boolean, noun: String): RoutePlan {
        val mode = if (hasDestination) RouteMode.UNKNOWN // 목적지가 있으면 질의 불필요
        else try {
            withTimeoutOrNull(timeoutMs) { provider.routeMode() } ?: RouteMode.UNKNOWN
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            RouteMode.UNKNOWN
        }
        return RoutePlans.decide(mode, hasDestination, noun)
    }
}

/** 알림 흐름이 쓰는 내비 요청 포트(실제 구현은 NaviController; 테스트는 가짜). */
interface NaviPort {
    suspend fun routeViaCandidate(dest: com.hmgcarapp.fuelwise.domain.Destination?, c: com.hmgcarapp.fuelwise.domain.Candidate, action: NaviAction): Long
    suspend fun awaitResult(id: Long, timeoutMs: Long = 8000L): NaviStatus?
    fun clearFinished()
    fun clearFinished(id: Long)
}
