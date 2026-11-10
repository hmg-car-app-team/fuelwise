package com.hmgcarapp.fuelwise.app

import android.content.BroadcastReceiver
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.PleosListener
import com.hmgcarapp.core.voice.PleosSpeaker
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.fuelwise.data.CandidateLoader
import com.hmgcarapp.fuelwise.data.StationRepositories
import com.hmgcarapp.fuelwise.domain.AlertEffect
import com.hmgcarapp.fuelwise.domain.AlertEvent
import com.hmgcarapp.fuelwise.domain.AlertFlow
import com.hmgcarapp.fuelwise.domain.AlertGate
import com.hmgcarapp.fuelwise.domain.AlertInput
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EnergyMode
import com.hmgcarapp.fuelwise.domain.EnergyResolver
import com.hmgcarapp.fuelwise.domain.EvalInput
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.MoveGate
import com.hmgcarapp.fuelwise.domain.Presets
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.ReportLogic
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.domain.SortKey
import com.hmgcarapp.fuelwise.domain.StoreRules
import com.hmgcarapp.fuelwise.domain.safelyCatching
import com.hmgcarapp.fuelwise.navi.NaviController
import com.hmgcarapp.fuelwise.navi.RouteAdvisor
import com.hmgcarapp.fuelwise.vehicle.PleosEnergySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 화면 상태의 단일 소유자이자 SDK/IO 효과 실행 배선. Activity 수명. 모든 상태 변경은 메인 스레드(Dispatchers.Main.immediate)에서.
 * 알림·음성·배너·팝업·토스트·내비 요청 소속의 상태 전이는 [AlertFlow] 가 하고([dispatch]), 효과 실행은 [AlertRunner] 가 한다.
 */
class AppController(
    private val context: Context,
    val signals: CarSignals,
    apiKey: String,
    private val speaker: Speaker = PleosSpeaker(context),
    private val listener: Listener = PleosListener(context),
    /** 달력 시각(리포트 기록·저장 파일 정리). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 경과 시간(재시도 간격·디바운스): 벽시계 변경에 영향받지 않는 단조 시계. */
    private val monoClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> HmgLog.w("App", "코루틴 예외(무시): $t") })
    private val stateStore = StateStore(LocalJsonStore.forApp(context))
    private val loader: CandidateLoader
    /** 위치 갱신으로 인한 재계산은 이전 계산 위치에서 100m 이상 움직였을 때만(후보 재로드 임계 2km 는 CandidateCache). */
    private val moveGate = MoveGate(RECOMPUTE_MOVE_KM)
    val debuggable: Boolean = SimulationHub.isDebuggable(context)
    val navi = NaviController(context, scope, debuggable)
    private val energySource = PleosEnergySource(context)

    private val _state = MutableStateFlow(UiState(debuggable = debuggable))
    val state: StateFlow<UiState> = _state
    private val runner = AlertRunner(scope, speaker, listener, navi, RouteAdvisor(navi), { _state.value }, debuggable, ::dispatch)
    private var defaultLoadJob: Job? = null
    private var saveJob: Job? = null
    /** AUTO 에서 차량 신호로 마지막으로 확인된 도메인(일시 null 로 EV<->FUEL 이 뒤집히지 않게). 메모리 전용. */
    private var stickyKind: EnergyKind? = null
    // init 에서 쓰이므로 init 보다 먼저 선언(선언 순서가 init 뒤면 초기화가 등록한 값을 덮어쓴다)
    private var simReceiver: BroadcastReceiver? = null
    /** 복원 전에는 저장·재계산·알림을 하지 않는다(기본값으로 덮어쓰기 방지). */
    private val restored: Boolean get() = !_state.value.loading

    init {
        val r = StationRepositories.create(context, apiKey)
        loader = CandidateLoader(r.repo, clock = monoClock)
        _state.update { it.copy(dataLabel = r.info.label) }
        // 시연 상태는 저장하지 않는다: 앱 시작은 항상 실신호.
        signals.hub.setDriveOverride(null); signals.hub.setEnabled(false)
        navi.connect()
        scope.launch { restore() }
        defaultLoadJob = scope.launch { applyLoad(UiState.DEFAULT_ORIGIN) }
        scope.launch {
            // flow 가 예외/완료로 끝나도 에너지 수집이 영구 중단되지 않게 간격을 두고 다시 시작한다(취소 시에만 종료)
            while (true) {
                energySource.readings().catch { HmgLog.w("Energy", "flow 실패 $it") }.collect { r2 -> safely("Energy") { _state.update { it.copy(energy = r2) }; recompute() } }
                HmgLog.w("Energy", "flow 종료 -> ${ENERGY_RESTART_MS}ms 뒤 재시작")
                delay(ENERGY_RESTART_MS)
            }
        }
        scope.launch {
            signals.location.catch { HmgLog.w("Location", "flow 실패 $it") }.collect { g: GeoPoint -> safely("Location") {
                val o = LatLon(g.latitude, g.longitude)
                if (!moveGate.accept(o)) return@safely
                // 첫 실위치: 기본 위치 로드는 더 이상 필요 없다(세대 id 로도 오래된 결과는 버려진다)
                if (!_state.value.originReal) defaultLoadJob?.cancel()
                _state.update { it.copy(origin = o) }
                dispatch(AlertEvent.LocationReady)
                if (loader.needsReload(o)) applyLoad(o)
                recompute()
            } }
        }
        // 후보가 비어 있는 동안 30초 주기로 재로드(주차 중 위치 갱신이 없어도). 로드 실패 후 공백 방지.
        scope.launch { while (true) { delay(RETRY_CHECK_MS); if (loader.retryDue()) applyLoad(_state.value.origin) } }
        scope.launch { delay(AlertGate.ORIGIN_TIMEOUT_MS); if (!_state.value.originReal) { dispatch(AlertEvent.OriginWaitElapsed); recompute() } }
        if (debuggable) simReceiver = SimReceiver.register(context, this, monoClock)
    }

    /** 이벤트를 [AlertFlow] 에 넣고 나온 효과를 실행한다(효과는 상태 갱신 람다 밖에서: 람다는 재시도될 수 있다). */
    internal fun dispatch(e: AlertEvent) {
        var fx: List<AlertEffect> = emptyList()
        val before = persistedCopy().armed
        _state.update { st -> AlertFlow.reduce(st.alert, e).let { (a, f) -> fx = f; st.copy(alert = a) } }
        fx.forEach { if (it is AlertEffect.SetArmed) updateP { p -> p.copy(armed = it.armed) } else runner.run(it) } // armed 는 AlertState 가 권위, 저장본은 따라간다
        // 알림 종료 등으로 디스크에 쓸 armed 가 바뀌면 보정본(true)을 현재 값으로 교체한다(p 가 안 바뀌어도; 무변경은 lastWritten 이 거른다, Fix-L)
        if (StoreRules.persistedArmedChanged(before, persistedCopy().armed)) scheduleSave()
    }

    /** 수집 본문의 예외가 수집 코루틴을 영구 종료시키지 않게 한다(취소는 그대로 전파). */
    private inline fun safely(tag: String, block: () -> Unit) =
        safelyCatching({ t -> HmgLog.w(tag, "수집 처리 예외(무시): $t") }, block) // Throwable 포함, 취소는 재던짐

    private suspend fun restore() {
        var res = StateStore.Restored(Persisted(), corrupt = false, quarantined = false, readError = false)
        try {
            res = withContext(Dispatchers.IO) { stateStore.restore(clock()) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            HmgLog.w("Store", "복원 실패 -> 기본값, 이번 세션 저장 보류: $t")
            stateStore.holdWrites()
            res = res.copy(readError = true)
        } finally {
            // 어떤 경우에도 loading=false(=복원 완료)를 보장한다(실패 시 기본값)
            applyRestored(res)
        }
    }

    private fun applyRestored(res: StateStore.Restored) {
        if (res.corrupt) HmgLog.w("Store", "저장 파일 손상 -> 기본값으로 시작(보존=${res.quarantined})")
        if (res.readError) HmgLog.w("Store", "저장 파일 읽기 오류 -> 기본값으로 동작, 이번 세션 저장 보류(원본 유지)")
        _state.update { it.copy(p = res.p, loading = false, saveHeld = res.readError, corruptKept = res.corrupt && res.quarantined) }
        dispatch(AlertEvent.Restored(res.p.armed))
        // 복원 안내도 주행 중에는 세팅하지 않는다(설정 화면의 상시 안내는 유지, r7R3-6)
        when {
            // 앱 시작 직후 drive 기본값이 DRIVING 이라 Notify 는 버려진다: 정차 시 한 번 표시한다(r9R3-1). 설정 화면 상시 안내는 별도
            res.corrupt -> dispatch(AlertEvent.NotifyOrDefer("저장된 데이터가 손상돼 처음 상태로 시작했어요"))
            res.readError -> dispatch(AlertEvent.NotifyOrDefer(StoreRules.HELD_NOTICE))
        }
        recompute()
    }

    /** 최신 origin 의 결과만 반영한다(오래된 로드는 [CandidateLoader] 가 버림). */
    private suspend fun applyLoad(origin: LatLon) {
        val list = loader.load(origin) ?: return
        _state.update { it.copy(candidates = list) }
        recompute()
    }

    /** 앱 시작/재개·권한 부여 시: 신호 폴링 게이트를 풀고 즉시 재시도, 내비 연결이 끊겨 있으면 재연결. */
    fun onAppStart() {
        energySource.retryNow(); navi.connect()
    }

    // ---------- 파생 상태 ----------
    private fun recompute() {
        if (!restored) return
        val prev = _state.value
        val p = prev.p
        val s = p.settings
        val e = prev.energy
        val mode = EnergyResolver.effectiveMode(p.simMode, s.energyMode) // 시연 모드는 메모리 전용이며 저장된 설정을 바꾸지 않는다
        val r = EnergyResolver.resolve(mode, s.fallbackLevelPct, p.simFuel, p.simSoc, p.simRange, e.fuelPercent, e.evSocPercent, e.rangeKm, stickyKind, e.fuelSettled, e.evSettled)
        stickyKind = r.sticky
        val cards = Presets.discounts.filter { it.id in p.cards }
        val qty = Recommender.quantityFor(r.kind, r.level, s)
        val near = Recommender.nearby(prev.candidates, prev.origin)
        val quotes = Recommender.sort(
            Recommender.evaluate(EvalInput(prev.origin, prev.destination?.pos, r.kind, s, cards, qty), near), s.sort,
        )
        // 후보가 하나도 없으면(로드 전·범위 밖) 저장된 선택을 건드리지 않는다
        val selected = if (quotes.isEmpty()) p.selectedId else p.selectedId?.takeIf { id -> quotes.any { it.candidate.id == id } } ?: quotes.first().candidate.id
        _state.value = prev.copy(
            p = p.copy(selectedId = selected), kind = r.kind, level = r.level, levelSource = r.source, rangeKm = r.rangeKm, quotes = quotes,
            modeNote = EnergyResolver.modeNote(mode, p.simMode != null),
        )
        val thr = if (r.kind == EnergyKind.FUEL) s.lowFuelPct else s.lowSocPct
        dispatch(AlertEvent.EnergyUpdated(AlertInput(r.real, r.rangeKm, thr, s.lowRangeKm, loader.loadedOnce, Recommender.best(quotes) != null, quotes.map { it.candidate.id })))
    }

    // ---------- 알림·내비 요청 (전이는 AlertFlow) ----------
    /** 시스템 주도 경로(잔량 판단/시연 수신기)이므로 DRIVING 가드 예외: 주행 중에도 알림은 떠야 한다. */
    fun fireAlert(reason: String) = dispatch(AlertEvent.AlertRequested(reason))

    fun dismissBanner() = dispatch(AlertEvent.BannerDismissed) // 주행 중 닫기 억제는 AlertFlow 규칙

    fun startGuidance(candidateId: String?) = parkedOnly {
        _state.value.candidates.firstOrNull { it.id == candidateId }?.let(runner::guide)
    }

    fun confirmReplace() = parkedOnly { dispatch(AlertEvent.ReplaceConfirmed) }

    /** 팝업 닫기(상태 소거 전용). */
    fun cancelReplace() = dispatch(AlertEvent.ReplaceCancelled)

    // ---------- 사용자 조작 (상태를 바꾸는 공개 메서드는 모두 DRIVING 가드) ----------
    private inline fun parkedOnly(block: () -> Unit) { if (_state.value.drive == DriveState.DRIVING) return; block() }
    private fun update(f: (UiState) -> UiState) { _state.update(f); scheduleSave() }
    /** 복원 전에는 편집을 받지 않는다(조작은 로딩 중 비활성이고, 알림·재계산도 복원 뒤에만 돈다). 복원은 빠르다. */
    private fun updateP(f: (Persisted) -> Persisted) {
        if (!restored) return
        update { it.copy(p = f(it.p)) }
    }

    fun setPage(p: Page) = parkedOnly { updateP { it.copy(page = p) } } // 화면 상태: 데이터 편집이 아니다(r7R2-1)
    fun select(id: String) = parkedOnly { updateP { it.copy(selectedId = id) } }
    fun editSettings(f: (Settings) -> Settings) = parkedOnly {
        updateP { it.copy(settings = StateCodec.sanitize(f(it.settings))) }; recompute()
    }
    fun setVoiceReply(on: Boolean) = editSettings { it.copy(voiceReply = on) }
    fun setSort(k: SortKey) = editSettings { it.copy(sort = k) }
    fun toggleCard(id: String) = parkedOnly { updateP { it.copy(cards = if (id in it.cards) it.cards - id else it.cards + id) }; recompute() }
    /** 샘플 목적지 선택: debuggable 시연 전용. */
    fun setDestination(index: Int?) { if (debuggable) editSettings { it.copy(destinationIndex = index) } }
    fun setSimFuel(v: Int?) = parkedOnly { if (debuggable) simFuel(v) }
    fun setSimSoc(v: Int?) = parkedOnly { if (debuggable) simSoc(v) }
    fun setSimRange(v: Float?) = parkedOnly { if (debuggable) simRange(v) }
    fun rearm() = parkedOnly { doRearm() }

    // 시연 수신기(debuggable 전용, 시스템 주도)용 비가드 실행부
    internal fun simFuel(v: Int?) { updateP { it.copy(simFuel = v) }; recompute() }
    internal fun simSoc(v: Int?) { updateP { it.copy(simSoc = v) }; recompute() }
    internal fun simRange(v: Float?) { updateP { it.copy(simRange = v) }; recompute() }
    internal fun simMode(v: EnergyMode?) { updateP { it.copy(simMode = v) }; recompute() }
    internal fun doRearm() { dispatch(AlertEvent.Rearm); recompute() }

    /**
     * 시연 주행 강제: debuggable 전용이며 DRIVING 만 효과가 있다(null = 실신호). DRIVING 에서 빠져나오는 유일한 수단이라
     * DRIVING 가드 예외다(실제 주행 상태를 낮추는 방향의 override 는 core 가 무시).
     */
    fun setDemoDrive(v: String?) {
        if (!debuggable) return
        val drive = if (v == "DRIVING") "DRIVING" else null
        val h = signals.hub
        if (drive != null) { h.setEnabled(true); h.setDriveOverride(DriveState.DRIVING) } else { h.setDriveOverride(null); h.setEnabled(false) }
        updateP { it.copy(demoDrive = drive) } // 메모리 전용(encode 제외)
    }

    /** 시스템 상태 반영(UX 제한/기어 판정 결과). 사용자 조작이 아니므로 가드 대상 아님. */
    fun onDriveState(d: DriveState) {
        if (_state.value.drive == d) return
        _state.update { it.copy(drive = d) }
        dispatch(AlertEvent.DriveChanged(d == DriveState.DRIVING))
    }

    /** 후보 선택 결과를 "주유/충전 완료"로 기록 -> 월 누적 리포트(추정 절약). 같은 후보 60초 내 중복 기록 금지. */
    fun recordSelected() = parkedOnly {
        val q = _state.value.selected ?: return@parkedOnly
        val now = clock()
        if (ReportLogic.isDuplicate(_state.value.p.report, q.candidate.name, now)) {
            dispatch(AlertEvent.Notify("방금 같은 곳을 기록했어요"))
            return@parkedOnly
        }
        val e = ReportEntry(now, q.candidate.name, q.candidate.kind, q.quantity, q.totalCost, q.netSaving)
        updateP { it.copy(report = StateCodec.capReport(it.report + e)) }
        dispatch(AlertEvent.Notify(StoreRules.recordedMessage(q.candidate.name, q.netSaving, _state.value.saveHeld)))
    }

    /** 시연용 샘플 기록(debuggable 전용). sample=true 라 합계에서 제외된다. */
    fun addSampleReport() = parkedOnly {
        if (!debuggable) return@parkedOnly
        val now = clock()
        val day = 86_400_000L
        val s = listOf(
            ReportEntry(now - 3 * day, "서초 알뜰주유소(샘플)", EnergyKind.FUEL, 38.0, 62_890, 1_350, true),
            ReportEntry(now - 10 * day, "탄천 알뜰주유소(샘플)", EnergyKind.FUEL, 41.5, 67_604, 2_120, true),
            ReportEntry(now - 18 * day, "양재 완속·급속 허브(샘플)", EnergyKind.EV, 32.0, 8_760, 410, true),
        )
        updateP { it.copy(report = StateCodec.capReport(it.report + s)) }
    }

    /**
     * 저장된 주유/충전 기록을 모두 지운다(리포트·설정 공통, 확인 팝업 뒤). 상태 파일·손상 보존본(.corrupt)·임시 파일을
     * 모두 지운 뒤 설정만 다시 즉시 기록한다.
     */
    fun clearReport() = parkedOnly {
        if (!restored) return@parkedOnly // 복원 전에는 디스크 원본을 지울 수 없다(UI 도 비활성, r7R4-2)
        val before = _state.value.p.report
        updateP { it.copy(report = emptyList()) }
        saveJob?.cancel()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { stateStore.clearAll { persistedCopy() } }
            if (ok) {
                dispatch(AlertEvent.DeferredDropped)
                // 전체 삭제로 저장이 다시 허용됐는지(writable)와 보류 표시·손상 보존본 표시를 동기화한다
                _state.update { it.copy(saveHeld = !stateStore.writable, corruptKept = false) }
                if (!stateStore.writable) dispatch(AlertEvent.NotifyOrDefer(StoreRules.HELD_NOTICE)) // 삭제 뒤 설정 재기록이 실패한 경우
            } else {
                // 삭제 실패: 디스크에는 기록이 남아 있으므로 메모리 목록도 되돌려 표시가 거짓이 되지 않게 한다(r7R4-1).
                // 그 사이 새로 기록한 항목은 유지. 보류(writable)·손상 보존본 표시는 그대로 둔다(r6R2-5, r6R4-5).
                updateP { it.copy(report = StateCodec.capReport(StoreRules.restoreAfterFailedClear(before, it.report))) }
                // 주행 중이면 버리지 않고 보관했다가 정차 시 한 번 표시한다(r7R2-7, r7R5-8)
                dispatch(AlertEvent.NotifyOrDefer("삭제하지 못했어요. 잠시 후 다시 시도해 주세요"))
            }
        }
    }

    /** 토스트 소비(UI 배관; 사용자 상태 변경 아님). */
    fun clearMessage() = dispatch(AlertEvent.MessageConsumed)

    // ---------- 저장/복원 ----------
    // 디스크에 쓰는 모든 경로(scheduleSave·saveNow·clearAll)는 같은 persistedCopy() 를 쓴다(Fix-L).
    // 인코딩은 항상 쓰기 직전(락 안)의 최신 상태로 한다: 오래된 스냅샷이 최신을 덮어쓰거나 삭제한 기록이 되살아나지 않는다.
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            if (!restored) return@launch
            // 쓰기 도중 취소돼도(재예약·onStop·삭제) 결과(FAILED→saveHeld 등)가 버려지지 않게 쓰기는 취소를 미룬다: 짧고 락으로 직렬화된다(Fix-M)
            onWriteResult(withContext(Dispatchers.IO + NonCancellable) { stateStore.writeLatest { persistedCopy() } })
        }
    }

    /** 쓰기 결과 반영: 성공일 때만 손상 보존본 표시 해제, 실패면 기존 저장 보류 표시·안내를 쓴다(새 경로 없음, r9R4-1). */
    private fun onWriteResult(r: WriteResult) {
        when (r) {
            WriteResult.WROTE -> releaseCorruptKept()
            WriteResult.FAILED -> {
                _state.update { it.copy(saveHeld = true) }
                dispatch(AlertEvent.NotifyOrDefer(StoreRules.HELD_NOTICE))
            }
            WriteResult.UNCHANGED, WriteResult.HELD -> Unit
        }
    }

    /** 디스크에 쓸 사본: 알림 진행 중이면 armed 를 true 로(라이브 [_state] 는 그대로, r9R5-1). */
    private fun persistedCopy(): Persisted {
        val st = _state.value
        return st.p.copy(armed = StoreRules.persistedArmed(st.alert.banner?.voiceStatus, st.p.armed, st.alert.inFlightId != null))
    }

    /** 정상 저장이 성공하면 LocalJsonStore 가 손상 보존본을 지운다: 표시도 해제한다(r6R1-1). */
    private fun releaseCorruptKept() { _state.update { if (it.corruptKept) it.copy(corruptKept = false) else it } }

    /** 수명주기(onStop/release)에서 프로세스가 죽기 전에 끝나야 하므로 동기 저장. 진행 중인 지연 저장은 취소한다. */
    fun saveNow() {
        if (!restored) return
        saveJob?.cancel()
        onWriteResult(stateStore.writeLatest { persistedCopy() })
    }

    fun release() {
        dispatch(AlertEvent.AppReleased) // 알림 흐름이 끝나기 전이면 armed 를 되돌린다(C12): 저장 전에 반영
        saveNow()
        simReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        runner.cancelVoice()
        navi.release(); speaker.release(); listener.release()
        scope.cancel()
    }

    companion object {
        const val STORE_NAME = "fuelwise_state"
        const val RECOMPUTE_MOVE_KM = 0.1
        const val RETRY_CHECK_MS = 5_000L
        const val ENERGY_RESTART_MS = 5_000L
    }
}
