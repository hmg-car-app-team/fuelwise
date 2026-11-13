package com.hmgcarapp.fuelwise.app

import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.fuelwise.domain.AlertState
import com.hmgcarapp.fuelwise.domain.Banner
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.Destination
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Presets
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.domain.RouteDestination
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.ReplaceConfirm
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.vehicle.EnergyReading

data class UiState(
    val p: Persisted = Persisted(),
    val candidates: List<Candidate> = emptyList(),
    val dataLabel: String = "불러오는 중",
    /** 헤더용 짧은 데이터 라벨 */
    /** 충전소 공공데이터 스냅샷 출처(실데이터일 때만; 가공 샘플이면 null) */
    val evMeta: com.hmgcarapp.fuelwise.data.EvMeta? = null,
    val origin: LatLon = DEFAULT_ORIGIN,
    val energy: EnergyReading = EnergyReading(),
    val kind: EnergyKind = EnergyKind.FUEL,
    val level: Int = 30,
    val levelSource: String = "설정값",
    val rangeKm: Float? = null,
    val quotes: List<Quote> = emptyList(),
    /** 기본 DRIVING(fail-closed): 첫 프레임에 정차 UI·정차 전용 조작이 열리지 않게. 실제 값은 UI 가 core 스냅샷으로 갱신한다. */
    val drive: DriveState = DriveState.DRIVING,
    /** 저장 상태 복원 전(첫 프레임 기본값 깜빡임 방지용). */
    val loading: Boolean = true,
    /** 알림·음성·배너·팝업·토스트·내비 요청 소속 상태(전이는 [com.hmgcarapp.fuelwise.domain.AlertFlow] 만 한다). */
    val alert: AlertState = AlertState(),
    /** 앱이 debuggable 인지(시연 컨트롤·내부 문구 노출 여부). */
    val debuggable: Boolean = false,
    /** 에너지 모드가 자동이 아닐 때 헤더에 붙일 안내('(설정: 전기)'). */
    /** 읽기 오류 또는 쓰기 실패로 이번 실행 동안 저장을 보류 중(설정 화면에 상시 표시). 세션 중 재읽기는 없다: 회복은 앱 재시작 또는 '기록 모두 삭제'(성공 시 쓰기 재개). */
    /** 내비 활성 경로의 실제 목적지(읽기 전용 질의 결과; 없거나 실패면 null). 평가에만 쓰고 안내 방식(경유지/직접)은 바꾸지 않는다. */
    val routeDest: RouteDestination? = null,
    val saveHeld: Boolean = false,
    /** 손상된 저장 파일을 보존본(.corrupt)으로 남긴 상태(삭제 버튼 활성 조건; 전체 삭제 때 해제). */
    val corruptKept: Boolean = false,
) {
    val banner: Banner? get() = alert.banner
    val message: String? get() = alert.message
    val replaceConfirm: ReplaceConfirm? get() = alert.replaceConfirm
    val originReal: Boolean get() = alert.originReal
    val settings: Settings get() = p.settings
    /** 샘플 목적지는 debuggable 시연에서만 유효. 기본은 목적지 없음. */
    val destination: Destination? get() = if (debuggable) settings.destinationIndex?.let { Presets.destinations.getOrNull(it) } else null
    /** 우회 평가에 쓰는 목적지: 시연 목적지가 우선, 없으면 내비 활성 경로의 목적지. 둘 다 없으면 null(왕복 근사). */
    val evalDest: LatLon? get() = destination?.pos ?: routeDest?.pos
    val selected: Quote? get() = quotes.firstOrNull { it.candidate.id == p.selectedId }
    val best: Quote? get() = Recommender.best(quotes)

    companion object { val DEFAULT_ORIGIN = LatLon(37.505, 127.0499) }
}
