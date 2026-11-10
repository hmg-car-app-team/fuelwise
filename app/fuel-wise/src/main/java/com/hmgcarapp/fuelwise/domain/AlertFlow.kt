package com.hmgcarapp.fuelwise.domain

import com.hmgcarapp.fuelwise.navi.NaviAction
import com.hmgcarapp.fuelwise.navi.NaviReducer
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.NaviText
import com.hmgcarapp.fuelwise.navi.RouteMode
import com.hmgcarapp.fuelwise.navi.RoutePlan

/**
 * message: 음성 안내 전문, line: 주행 중 한 줄(<=40자), voiceStatus: 한 단어([VoiceStatus]).
 * action/noun: 알림을 만들 때 확인한 경로 상태에 따른 내비 동작과 대상 명칭. id: 자동 소거 대조용.
 */
data class Banner(
    val message: String, val line: String, val candidateId: String?, val voiceStatus: String,
    val action: NaviAction = NaviAction.REQUEST_ROUTE, val noun: String = "주유소", val id: Long = 0L,
    /** 알림 때 확인한 경로 상태(배너 버튼 라벨용; 수동 누름은 실행 직전에 다시 확인한다). */
    val mode: RouteMode = RouteMode.NONE,
) {
    /** 경로를 덮어쓸 수 있는 알림인지(단음절 음성 수락 금지). 규칙은 [RoutePlan.canOverwrite] 하나. */
    val canOverwrite: Boolean get() = RoutePlan(mode, action, "").canOverwrite
}

/** 경로 상태를 확인하지 못한 채 수동으로 안내를 시작하려 할 때 덮어쓰기 확인을 받기 위한 상태. */
data class ReplaceConfirm(val candidateId: String, val noun: String)

/** [AlertFlow] 가 소유하는 알림·음성·배너·팝업·토스트·내비 요청 소속 상태 전부(UI 상태 [com.hmgcarapp.fuelwise.app.UiState] 가 한 필드로 담는다). */
data class AlertState(
    /** 잔량 알림 무장 여부(권위 있는 값; 저장본 `Persisted.armed` 는 [AlertEffect.SetArmed] 로 따라간다). */
    val armed: Boolean = true,
    val banner: Banner? = null,
    /** 현재 배너의 음성 수락/배너 버튼이 보낸 내비 요청 번호(배너 소거 때 그 결과만 지운다, N3). */
    val bannerReqId: Long = NaviReducer.IGNORED,
    /** 알림 흐름이 시작돼 끝나기 전의 알림 번호(배너 전 경로 확인 구간 포함; release 시 armed 복구 판단). null = 없음. */
    val inFlightId: Long? = null,
    val seq: Long = 0L,
    /** 이번 알림이 음성으로 안내한 후보(알림 시점의 추천). null = 후보 없음. */
    val target: String? = null,
    val originReal: Boolean = false,
    /** 실위치를 [AlertGate.ORIGIN_TIMEOUT_MS] 기다려도 못 받았음(N1): 이후엔 기본 위치 기준으로 알린다. */
    val originWaitExpired: Boolean = false,
    val replaceConfirm: ReplaceConfirm? = null,
    /** 기본 true(fail-closed). UI 의 drive 와 같은 이벤트([AlertEvent.DriveChanged])로 갱신된다. */
    val driving: Boolean = true,
    val message: String? = null,
    /** 주행 중에 생겨 정차 시 한 번 보여 줄 안내(삭제 실패 등). */
    val deferredMessage: String? = null,
)

/** 잔량 판단 입력(컨트롤러가 파생 상태에서 만든다). */
data class AlertInput(
    val levelPct: Int?, val rangeKm: Float?, val thresholdPct: Int, val thresholdRangeKm: Int,
    val loadDone: Boolean, val hasBest: Boolean, val candidateIds: Collection<String>,
)

/** 음성 청취 결과(SDK [com.hmgcarapp.core.voice.ListenResult] 의 순수 표현). */
sealed interface Heard {
    data class Text(val text: String) : Heard
    data object Failure : Heard
    data object Timeout : Heard
}

sealed interface AlertEvent {
    data class Restored(val armed: Boolean) : AlertEvent
    data object LocationReady : AlertEvent
    data object OriginWaitElapsed : AlertEvent
    data class EnergyUpdated(val input: AlertInput) : AlertEvent
    data class DriveChanged(val driving: Boolean) : AlertEvent
    /** 시스템 주도(잔량 판단 외 시연·시험 알림 포함). */
    data class AlertRequested(val reason: String) : AlertEvent
    data class AlertPlanned(val id: Long, val reason: String, val plan: RoutePlan, val noun: String, val best: Quote?, val fuel: Boolean) : AlertEvent
    data class VoiceSpoken(val id: Long, val spoke: Boolean, val voiceReply: Boolean) : AlertEvent
    data class VoiceHeard(val id: Long, val heard: Heard) : AlertEvent
    data class NaviRequested(val reqId: Long, val fromBanner: Boolean) : AlertEvent
    data class NaviResult(val alertId: Long?, val status: NaviStatus?) : AlertEvent
    data class ResultSpoken(val id: Long) : AlertEvent
    data class AlertFailed(val id: Long) : AlertEvent
    data class ExpiryElapsed(val id: Long) : AlertEvent
    data object BannerDismissed : AlertEvent
    data object Rearm : AlertEvent
    data class ReplaceAsked(val candidateId: String, val noun: String) : AlertEvent
    data object ReplaceConfirmed : AlertEvent
    data object ReplaceCancelled : AlertEvent
    data class ManualNavi(val candidateId: String, val action: NaviAction) : AlertEvent
    /** adb 시연 `accept`: 현재 배너의 후보·동작으로 요청. */
    data object AcceptBanner : AlertEvent
    data class Notify(val text: String) : AlertEvent
    data class NotifyOrDefer(val text: String) : AlertEvent
    data object DeferredDropped : AlertEvent
    data object MessageConsumed : AlertEvent
    data object AppReleased : AlertEvent
}

sealed interface AlertEffect {
    /** 진행 중인 알림 음성 흐름·소거 타이머를 취소하고 TTS 를 멈춘다. */
    data object CancelVoice : AlertEffect
    /** 활성 경로를 확인한 뒤 [AlertEvent.AlertPlanned] 를 되먹인다. */
    data class PlanAlert(val id: Long, val reason: String) : AlertEffect
    /** 발화 완료까지 기다린 뒤 [AlertEvent.VoiceSpoken] 을 되먹인다. */
    data class Speak(val id: Long, val text: String) : AlertEffect
    /** 1회 청취 뒤 [AlertEvent.VoiceHeard] 를 되먹인다. [ttsOk] 는 로그용. */
    data class Listen(val id: Long, val ttsOk: Boolean) : AlertEffect
    /** 내비 요청 -> [AlertEvent.NaviRequested] -> 결과 대기 -> [AlertEvent.NaviResult]. [alertId] 가 있으면 음성 수락 흐름. */
    data class StartNavi(val candidateId: String?, val action: NaviAction, val fromBanner: Boolean, val alertId: Long?) : AlertEffect
    /** 결과 확인 발화 뒤 [AlertEvent.ResultSpoken] 을 되먹인다. */
    data class SpeakResult(val id: Long, val text: String) : AlertEffect
    data class ScheduleExpiry(val id: Long, val delayMs: Long) : AlertEffect
    /** null = 처리 중이 아닌 모든 낡은 결과, 값 = 그 요청의 결과만. */
    data class ClearNaviFinished(val reqId: Long?) : AlertEffect
    data class SetArmed(val armed: Boolean) : AlertEffect
    data class Warn(val text: String) : AlertEffect
}

/**
 * 알림·음성·배너·팝업·토스트·내비 요청 소속의 상태 전이(순수, Android 의존 없음).
 * 컨트롤러는 효과를 실행하고 그 결과를 이벤트로 되먹일 뿐 상태를 직접 바꾸지 않는다. 지연은 효과([AlertEffect.ScheduleExpiry])와
 * 경과 이벤트([AlertEvent.ExpiryElapsed]/[AlertEvent.OriginWaitElapsed])로만 표현하므로 시계가 필요 없다.
 * 규칙 자체는 [AlertGate]·[VoiceOutcome]·[BannerRules]·[ReplaceRules]·[MessageRules]·[NaviReducer] 를 그대로 쓴다.
 */
object AlertFlow {
    private val NONE: List<AlertEffect> = emptyList()

    fun reduce(s: AlertState, e: AlertEvent): Pair<AlertState, List<AlertEffect>> = when (e) {
        is AlertEvent.Restored -> s.copy(armed = e.armed) to NONE
        AlertEvent.LocationReady -> s.copy(originReal = true) to NONE
        AlertEvent.OriginWaitElapsed -> (if (s.originReal) s else s.copy(originWaitExpired = true)) to NONE
        is AlertEvent.EnergyUpdated -> energy(s, e.input)
        is AlertEvent.DriveChanged -> drive(s, e.driving)
        is AlertEvent.AlertRequested -> start(s, e.reason)
        is AlertEvent.AlertPlanned -> planned(s, e)
        is AlertEvent.VoiceSpoken -> spoken(s, e)
        is AlertEvent.VoiceHeard -> heard(s, e)
        is AlertEvent.NaviRequested ->
            (if (e.fromBanner && e.reqId != NaviReducer.IGNORED) s.copy(bannerReqId = e.reqId) else s) to NONE
        is AlertEvent.NaviResult ->
            if (e.alertId != null && s.inFlightId == e.alertId) s to listOf(AlertEffect.SpeakResult(e.alertId, NaviText.spoken(e.status))) else s to NONE
        is AlertEvent.ResultSpoken -> if (s.inFlightId == e.id) finish(s, e.id, null) else s to NONE
        is AlertEvent.AlertFailed -> if (s.inFlightId == e.id) finish(s, e.id, VoiceStatus.FAILED) else s to NONE
        is AlertEvent.ExpiryElapsed -> if (s.banner?.id == e.id) clearBanner(s, cancelVoice = false) else s to NONE
        AlertEvent.BannerDismissed -> if (s.driving) s to NONE else clearBanner(s) // 사용자 닫기는 정차 시만
        AlertEvent.Rearm -> clearBanner(s.copy(armed = true)).let { (n, fx) -> n to (listOf(AlertEffect.SetArmed(true)) + fx) }
        is AlertEvent.ReplaceAsked -> (if (s.driving) s else s.copy(replaceConfirm = ReplaceConfirm(e.candidateId, e.noun))) to NONE
        AlertEvent.ReplaceConfirmed -> s.replaceConfirm?.let { rc ->
            s.copy(replaceConfirm = null) to listOf(AlertEffect.StartNavi(rc.candidateId, NaviAction.REQUEST_ROUTE, s.banner?.candidateId == rc.candidateId, null))
        } ?: (s to NONE)
        AlertEvent.ReplaceCancelled -> s.copy(replaceConfirm = null) to NONE
        is AlertEvent.ManualNavi -> s to listOf(AlertEffect.StartNavi(e.candidateId, e.action, s.banner?.candidateId == e.candidateId, null))
        AlertEvent.AcceptBanner -> {
            val b = s.banner; val cid = b?.candidateId
            if (b != null && cid != null) s to listOf(AlertEffect.StartNavi(cid, b.action, true, null))
            else s to listOf(AlertEffect.Warn("SIM 무시: accept (배너/후보 없음)"))
        }
        is AlertEvent.Notify -> s.copy(message = MessageRules.set(s.message, e.text, s.driving)) to NONE
        is AlertEvent.NotifyOrDefer ->
            MessageRules.showOrDefer(s.message, s.deferredMessage, e.text, s.driving).let { (shown, held) -> s.copy(message = shown, deferredMessage = held) to NONE }
        AlertEvent.DeferredDropped -> s.copy(deferredMessage = null) to NONE
        AlertEvent.MessageConsumed -> s.copy(message = null) to NONE
        AlertEvent.AppReleased -> {
            // 알림 음성 흐름(말하는 중/듣는 중)이 끝나기 전에 닫히면 armed 를 되돌려 다음에 다시 알리게 한다(C12)
            val armed = BannerRules.armedOnRelease(s.banner?.voiceStatus, s.armed, s.inFlightId != null)
            if (armed != s.armed) s.copy(armed = armed) to listOf(AlertEffect.SetArmed(armed)) else s to NONE
        }
    }

    private fun energy(s0: AlertState, i: AlertInput): Pair<AlertState, List<AlertEffect>> {
        val s = s0.copy(banner = s0.banner?.let { b -> b.copy(candidateId = BannerRules.staleSafe(b.candidateId, i.candidateIds)) })
        // 후보 로드 전·실위치 대기 중에는 판단을 보류한다(armed 소진 금지, N1). 후보 0개면 '후보 없음' 알림 1회(M4)
        val d = AlertGate.decide(i.levelPct, i.rangeKm, i.thresholdPct, i.thresholdRangeKm, s.armed, i.loadDone, i.hasBest, s.originReal || s.originWaitExpired)
        var cur = s
        val fx = mutableListOf<AlertEffect>()
        if (d.armed != s.armed) {
            cur = cur.copy(armed = d.armed); fx += AlertEffect.SetArmed(d.armed)
            if (BannerRules.clearOnRearm(s.armed, d.armed)) clearBanner(cur).let { (n, f) -> cur = n; fx += f } // 잔량 회복·재무장: 지난 알림 배너 소거
        }
        if (d.trigger) start(cur, d.reason ?: "잔량 부족").let { (n, f) -> cur = n; fx += f }
        return cur to fx
    }

    private fun drive(s: AlertState, driving: Boolean): Pair<AlertState, List<AlertEffect>> {
        if (s.driving == driving) return s to NONE
        // 정차 복귀: 주행 중 보관된 안내를 한 번 표시. 주행 진입: 떠 있던 토스트·덮어쓰기 팝업(N2)은 폐기(보관분은 유지)
        val (msg, held) = MessageRules.releaseDeferred(MessageRules.onDriveChange(s.message, driving), s.deferredMessage, driving)
        val next = s.copy(driving = driving, replaceConfirm = ReplaceRules.visible(s.replaceConfirm, driving), message = msg, deferredMessage = held)
        // 주행 진입 시 지난 내비 결과를 치운다(r7R3-7). 처리 중 요청은 유지된다.
        return next to (if (driving) listOf(AlertEffect.ClearNaviFinished(null)) else NONE)
    }

    /** 새 알림 시작: 이전 음성 흐름을 끊고 이전 요청의 결과가 새 배너에 붙지 않게 한다(N3). 시스템 주도라 주행 가드 없음. */
    private fun start(s: AlertState, reason: String): Pair<AlertState, List<AlertEffect>> {
        val id = s.seq + 1
        return s.copy(seq = id, inFlightId = id, bannerReqId = NaviReducer.IGNORED) to
            listOf(AlertEffect.CancelVoice, AlertEffect.ClearNaviFinished(null), AlertEffect.PlanAlert(id, reason))
    }

    private fun planned(s: AlertState, e: AlertEvent.AlertPlanned): Pair<AlertState, List<AlertEffect>> {
        if (s.inFlightId != e.id) return s to NONE
        val unit = if (e.fuel) "주유" else "충전"
        val msg = AlertText.voice(e.reason, e.best, unit, e.plan.ask, locationUnverified = !s.originReal)
        val b = Banner(msg, AlertText.line(e.reason, e.best), e.best?.candidate?.id, VoiceStatus.SPEAKING, e.plan.action, e.noun, e.id, e.plan.mode)
        return s.copy(banner = b, target = e.best?.candidate?.id) to listOf(AlertEffect.Speak(e.id, msg))
    }

    private fun spoken(s: AlertState, e: AlertEvent.VoiceSpoken): Pair<AlertState, List<AlertEffect>> {
        if (s.inFlightId != e.id) return s to NONE
        // 후보가 없거나 '음성 응답 사용'이 꺼져 있으면 청취하지 않는다(화면 배너+TTS 만). 발화 중 설정이 바뀔 수 있어 현재 값을 받는다
        if (s.target == null || !e.voiceReply) return finish(s, e.id, VoiceStatus.VOICE_ONLY)
        return withVoice(s, VoiceStatus.LISTENING) to listOf(AlertEffect.Listen(e.id, e.spoke))
    }

    private fun heard(s: AlertState, e: AlertEvent.VoiceHeard): Pair<AlertState, List<AlertEffect>> {
        if (s.inFlightId != e.id) return s to NONE
        val b = s.banner
        return when (val h = e.heard) {
            Heard.Failure -> finish(s, e.id, VoiceStatus.FAILED)
            Heard.Timeout -> finish(s, e.id, VoiceStatus.NO_REPLY)
            is Heard.Text -> {
                // 음성 수락만 알림 시점에 결정된 action 을 쓴다. 덮어쓰기 가능이면 다음절 명시 표현만 수락(N4)
                val outcome = VoiceOutcome.of(h.text, b?.canOverwrite ?: false)
                val cid = s.target
                if (outcome == VoiceStatus.ACCEPTED && b != null && cid != null)
                    withVoice(s, outcome) to listOf(AlertEffect.StartNavi(cid, b.action, true, e.id)) // 결과 확인 발화까지 흐름 유지
                else finish(s, e.id, outcome)
            }
        }
    }

    private fun withVoice(s: AlertState, status: String) = s.copy(banner = s.banner?.copy(voiceStatus = status))

    /** 음성 흐름 종료: 상태를 정하고(null 이면 유지) 자동 소거를 예약한다(시스템 소거: 주행 가드 대상 아님). */
    private fun finish(s: AlertState, id: Long, status: String?): Pair<AlertState, List<AlertEffect>> {
        val n = (if (status != null) withVoice(s, status) else s).copy(inFlightId = null)
        return n to listOf(AlertEffect.ScheduleExpiry(id, BannerRules.TTL_MS))
    }

    /** 배너를 치우고(재무장·닫기: [cancelVoice] 로 흐름도 끊음) 이 배너가 보낸 요청의 결과만 지운다. */
    private fun clearBanner(s: AlertState, cancelVoice: Boolean = true): Pair<AlertState, List<AlertEffect>> {
        val fx = buildList {
            if (cancelVoice) add(AlertEffect.CancelVoice)
            if (s.bannerReqId != NaviReducer.IGNORED) add(AlertEffect.ClearNaviFinished(s.bannerReqId))
        }
        val n = s.copy(banner = null, bannerReqId = NaviReducer.IGNORED, inFlightId = if (cancelVoice) null else s.inFlightId)
        return n to fx
    }
}
