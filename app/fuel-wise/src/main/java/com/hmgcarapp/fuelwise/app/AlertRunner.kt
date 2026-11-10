package com.hmgcarapp.fuelwise.app

import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.fuelwise.domain.AlertEffect
import com.hmgcarapp.fuelwise.domain.AlertEvent
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.Heard
import com.hmgcarapp.fuelwise.domain.mustRethrow
import com.hmgcarapp.fuelwise.navi.GuidanceStep
import com.hmgcarapp.fuelwise.navi.NaviPort
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.RouteAdvisor
import com.hmgcarapp.fuelwise.navi.RoutePlans
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [com.hmgcarapp.fuelwise.domain.AlertFlow] 의 효과를 SDK(TTS·STT·내비)로 실행하고 결과를 이벤트로 되먹인다.
 * 상태 전이는 하지 않는다(여기서 읽는 것은 현재 화면 상태 스냅샷뿐). 모든 호출은 메인 스레드.
 * 코루틴은 LAZY 로 만들어 슬롯에 넣은 뒤 시작한다: 즉시 실행 디스패처에서 본문이 동기로 다음 효과(=슬롯 교체)를 일으켜도 새 job 을 잃지 않는다.
 */
class AlertRunner(
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val listener: Listener,
    private val navi: NaviPort,
    private val advisor: RouteAdvisor,
    private val ui: () -> UiState,
    private val debuggable: Boolean,
    private val dispatch: (AlertEvent) -> Unit,
) {
    private var alertJob: Job? = null
    private var expiryJob: Job? = null
    private var guidanceJob: Job? = null

    fun run(fx: AlertEffect) {
        when (fx) {
            AlertEffect.CancelVoice -> cancelVoice()
            is AlertEffect.PlanAlert -> alert(fx.id) {
                val st0 = ui()
                val noun = RoutePlans.nounOf((st0.best?.candidate?.kind ?: st0.kind) == EnergyKind.EV)
                val plan = advisor.plan(st0.destination != null, noun) // 문구를 만들기 전에 활성 경로 확인(덮어쓰기 방지)
                val st = ui()
                dispatch(AlertEvent.AlertPlanned(fx.id, fx.reason, plan, noun, st.best, st.kind == EnergyKind.FUEL))
            }
            is AlertEffect.Speak -> alert(fx.id) {
                // false 는 TTS 실패뿐 아니라 시간 초과·교체일 수도 있다: 후보가 있으면 청취는 계속 진행한다
                dispatch(AlertEvent.VoiceSpoken(fx.id, speaker.speakAndAwait(fx.text), ui().settings.voiceReply))
            }
            is AlertEffect.Listen -> alert(fx.id) {
                val heard = when (val r = listener.listenOnce(LISTEN_MS)) {
                    is ListenResult.Text -> { if (debuggable) HmgLog.i("Voice", "STT: ${r.text}"); Heard.Text(r.text) } // 발화 원문은 debuggable 에서만 로그
                    is ListenResult.Failure -> { HmgLog.w("Voice", "STT 실패: ${r.reason} (TTS ${if (fx.ttsOk) "정상" else "미완료"})"); Heard.Failure }
                    ListenResult.Timeout -> Heard.Timeout
                }
                dispatch(AlertEvent.VoiceHeard(fx.id, heard))
            }
            is AlertEffect.StartNavi -> {
                val body: suspend () -> Unit = {
                    val c = ui().candidates.firstOrNull { it.id == fx.candidateId }
                    val res: NaviStatus? = if (c == null) null else {
                        val reqId = navi.routeViaCandidate(ui().destination, c, fx.action)
                        dispatch(AlertEvent.NaviRequested(reqId, fx.fromBanner))
                        navi.awaitResult(reqId) // 자기 요청의 결과만 기다린다
                    }
                    dispatch(AlertEvent.NaviResult(fx.alertId, res))
                }
                if (fx.alertId != null) alert(fx.alertId) { body() } else launchInto({ guidanceJob = it }) { guarded(null) { body() } }
            }
            is AlertEffect.SpeakResult -> alert(fx.id) { speaker.speakAndAwait(fx.text); dispatch(AlertEvent.ResultSpoken(fx.id)) } // 결과 확인 발화(짧게)
            is AlertEffect.ScheduleExpiry -> { expiryJob?.cancel(); expiryJob = scope.launch { delay(fx.delayMs); dispatch(AlertEvent.ExpiryElapsed(fx.id)) } }
            is AlertEffect.ClearNaviFinished -> if (fx.reqId == null) navi.clearFinished() else navi.clearFinished(fx.reqId)
            is AlertEffect.Warn -> HmgLog.w("FuelWise", fx.text)
            is AlertEffect.SetArmed -> Unit // 저장 상태는 컨트롤러가 반영한다
        }
    }

    /**
     * 사용자 조작(정차 시만): 배너·상세 버튼 모두 **실행 직전에 경로 상태를 다시 확인**(최대 ≈4초)해 현재 상태 기준으로 동작을 정한다.
     * 확인 불가(UNKNOWN)면 기존 경로를 덮어쓰기 전에 확인 팝업을 띄운다 - 배너 경로에서도 동일.
     */
    fun guide(c: Candidate) {
        if (guidanceJob?.isActive == true) return // 재확인 중·요청 대기 중 중복 누름 무시
        launchInto({ guidanceJob = it }) {
            val noun = RoutePlans.nounOf(c.kind == EnergyKind.EV)
            val plan = advisor.plan(ui().destination != null, noun)
            if (ui().drive == DriveState.DRIVING) return@launchInto // 재확인 중 주행이 시작되면 중단
            when (val step = RoutePlans.manualStep(plan)) {
                GuidanceStep.ConfirmReplace -> dispatch(AlertEvent.ReplaceAsked(c.id, noun))
                is GuidanceStep.Execute -> dispatch(AlertEvent.ManualNavi(c.id, step.action))
            }
        }
    }

    fun cancelVoice() { alertJob?.cancel(); alertJob = null; expiryJob?.cancel(); expiryJob = null; speaker.stop() }

    private fun alert(id: Long, block: suspend () -> Unit) = launchInto({ alertJob = it }) { guarded(id) { block() } }

    private fun launchInto(slot: (Job) -> Unit, block: suspend () -> Unit) {
        val j = scope.launch(start = CoroutineStart.LAZY) { block() }
        slot(j); j.start()
    }

    /** 흐름이 예외로 끝나도 배너가 '발화 중'에 고착되지 않게 실패 이벤트로 닫는다(취소·VM 치명 오류는 삼키지 않는다). */
    private suspend inline fun guarded(id: Long?, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            speaker.stop() // STT 는 listenOnce 가 finally 로 정리한다
            throw e
        } catch (e: Throwable) {
            if (mustRethrow(e)) throw e
            HmgLog.w("Alert", "알림 흐름 예외: ${e.javaClass.simpleName}")
            if (id != null) dispatch(AlertEvent.AlertFailed(id))
        }
    }

    companion object { const val LISTEN_MS = 8000L }
}
