package com.hmgcarapp.fuelwise.domain

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 에너지 종류·잔량·출처·주행가능거리 결정(순수). AppController.recompute 에서 분리.
 * [sticky]: AUTO 에서 **차량 신호로 마지막으로 확인된 도메인**(일시 null 로 EV<->FUEL 이 뒤집히지 않게 유지).
 * M7: sticky 는 FUEL·EV **두 도메인이 한 사이클(값 또는 오류/타임아웃으로 확정)을 마친 뒤에만** 확정한다.
 */
data class ResolvedEnergy(val kind: EnergyKind, val real: Int?, val level: Int, val source: String, val rangeKm: Float?, val sticky: EnergyKind? = null)

object EnergyResolver {
    /**
     * [mode] 는 실효 모드: 시연 모드(메모리 전용)가 있으면 그것, 없으면 저장된 설정.
     * AUTO 판정 순서: 시연 값 -> (둘 다 실신호면) [lastKind] 유지 -> 연료 -> 전기 -> 신호 없음이면 [lastKind] 또는 내연기관.
     */
    fun resolve(
        mode: EnergyMode, fallbackLevelPct: Int,
        simFuel: Int?, simSoc: Int?, simRangeKm: Float?,
        fuelPct: Int?, evSocPct: Int?, rangeKm: Float?,
        lastKind: EnergyKind? = null,
        fuelSettled: Boolean = true, evSettled: Boolean = true,
    ): ResolvedEnergy {
        // 한 도메인이라도 아직 첫 응답/실패 확정 전이면 마지막 확정값(lastKind) 없이 잠정 판정하고 sticky 는 바꾸지 않는다
        val cycleDone = fuelSettled && evSettled
        val last = if (cycleDone) lastKind else null
        val kind = when (mode) {
            EnergyMode.FUEL -> EnergyKind.FUEL
            EnergyMode.EV -> EnergyKind.EV
            EnergyMode.AUTO -> when {
                simFuel != null -> EnergyKind.FUEL
                simSoc != null -> EnergyKind.EV
                fuelPct != null && evSocPct != null -> last ?: EnergyKind.FUEL
                fuelPct != null -> EnergyKind.FUEL
                evSocPct != null -> EnergyKind.EV
                else -> last ?: EnergyKind.FUEL
            }
        }
        val sim = if (kind == EnergyKind.FUEL) simFuel else simSoc
        val sdk = if (kind == EnergyKind.FUEL) fuelPct else evSocPct
        val real = sim ?: sdk
        val src = when {
            sim != null -> "시뮬레이션"
            sdk != null -> "차량 신호"
            else -> "설정값(신호 없음)"
        }
        // 시연 주행가능거리 0(또는 음수/NaN)은 '값 없음'으로 보고 실신호를 쓴다
        val range = simRangeKm?.takeIf { it.isFinite() && it > 0f } ?: rangeKm
        val sticky = if (mode == EnergyMode.AUTO && sim == null && sdk != null && cycleDone) kind else lastKind
        return ResolvedEnergy(kind, real, real ?: fallbackLevelPct, src, range, sticky)
    }

    /** 실효 모드: 시연 모드(메모리 전용)가 있으면 그것, 없으면 저장된 설정. 시연 모드는 저장된 설정을 바꾸지 않는다. */
    fun effectiveMode(simMode: EnergyMode?, setting: EnergyMode): EnergyMode = simMode ?: setting

    /** 모드가 자동이 아니면 헤더에 붙일 안내('(설정: 전기)'). 시연 모드는 '(시연: …)'. */
    fun modeNote(mode: EnergyMode, simulated: Boolean): String? =
        if (mode == EnergyMode.AUTO) null else "(${if (simulated) "시연" else "설정"}: ${mode.label})"
}

/** 이전 기준 위치에서 [thresholdKm] 이상 움직였을 때만 true(첫 호출은 항상 true). 통과하면 기준 위치를 갱신한다. */
class MoveGate(private val thresholdKm: Double) {
    var last: LatLon? = null
        private set

    fun accept(p: LatLon): Boolean {
        val l = last
        if (l == null || CostEngine.haversineKm(l, p) >= thresholdKm) { last = p; return true }
        return false
    }
}

/** 알림 문구(순수). 후보명 뒤 조사는 [Josa] 로 맞춘다. */
object AlertText {
    const val BANNER_MAX = 40
    /** 실위치를 받지 못한 채 알릴 때(N1) 덧붙이는 안내. */
    const val LOCATION_NOTE = "현재 위치를 확인하지 못해 기본 위치 기준이에요."

    /**
     * [ask] 는 마지막 질문([RoutePlans] 가 경로 상태에 맞춰 만든다). 후보명이 소문자 섞인 영어로 끝나면 조사를 추정하지 않고
     * '추천 후보는 NAME.' 으로 문장을 재구성한다.
     */
    fun voice(reason: String, best: Quote?, unit: String, ask: String, locationUnverified: Boolean = false): String {
        if (best == null) return "$reason. 근처 $unit 후보를 찾지 못했어요."
        val name = best.candidate.name
        val km = String.format(Locale.KOREA, "%.1f", best.detourKm)
        val lead = if (Josa.isNeutral(name)) "추천 후보는 $name." else null
        fun sentence(pred: String) = if (lead != null) "$lead $pred" else "${Josa.subject(name)} $pred"
        val assumed = best.candidate.priceAssumed
        val basis = when {
            assumed && best.isBaseline -> sentence("우회가 가장 적어 추천해요(대략 ${km}km). 요금은 가정 단가예요.")
            assumed -> sentence("우회·대기 기준으로 추천해요(우회 대략 ${km}km). 요금은 가정 단가예요.")
            best.isBaseline -> sentence("우회가 가장 적어 추천해요(대략 ${km}km).")
            best.netSaving > 0 -> sentence("대략 ${km}km 돌아가도 실질 ${String.format(Locale.KOREA, "%,d", best.netSaving)}원 절약돼요.")
            else -> sentence("실질 비용이 가장 낮아요(우회 대략 ${km}km).")
        }
        val note = if (locationUnverified) " $LOCATION_NOTE" else ""
        return "$reason.$note $basis $ask"
    }

    /**
     * 주행 중 배너 한 줄(최대 [BANNER_MAX]자): '이유 · 후보명 · 약 N원 절약'. 절약이 있으면 후보명을 줄여서라도 절약액을 남긴다.
     */
    fun line(reason: String, best: Quote?): String {
        if (best == null) return shorten("$reason · 근처 후보를 찾지 못했어요")
        val tail = if (best.candidate.priceAssumed) " · 가정 단가" else if (!best.isBaseline && best.netSaving > 0) " · 약 ${String.format(Locale.KOREA, "%,d", best.netSaving)}원 절약" else ""
        val head = "$reason · "
        val room = (BANNER_MAX - head.length - tail.length).coerceAtLeast(4)
        return shorten(head + shorten(best.candidate.name, room) + tail)
    }

    /** 알림이 없을 때 주행 중 한 줄 상태. */
    fun idleLine(rangeKm: Float?): String =
        if (rangeKm != null && rangeKm.isFinite()) "알림 대기 중 · 주행가능거리 ${String.format(Locale.KOREA, "%.0f", rangeKm)}km" else "알림 대기 중"

    fun shorten(s: String, max: Int = BANNER_MAX) = if (s.length <= max) s else s.take(max - 1) + "…"
}

/** 음성 상태: 짧은 고정 단어(STT 원문·실패 reason 은 로그로만). */
object VoiceStatus {
    const val SPEAKING = "발화 중"
    const val LISTENING = "듣는 중"
    const val ACCEPTED = "수락"
    const val DECLINED = "거절"
    /** 경로를 바꾸는 질문에 '네/응' 같은 단음절로 답해 수락으로 보지 않은 경우(N4). 거절과 구분한다. */
    const val NOT_ACCEPTED = "수락 안 됨"
    const val FAILED = "실패"
    const val NO_REPLY = "무응답"
    /** 듣지 않고 안내만 한 경우(후보 없음 또는 음성 응답 끔). */
    const val VOICE_ONLY = "음성 안내만"
}

/** 월 누적 리포트(순수; 시계·시간대 주입). */
object ReportLogic {
    /** 같은 후보를 이 시간 안에 다시 기록하면 중복으로 본다. */
    const val DEDUP_MS = 60_000L

    fun monthKey(ts: Long, tz: TimeZone = TimeZone.getDefault()): String =
        Calendar.getInstance(tz).apply { timeInMillis = ts }.let { String.format(Locale.US, "%04d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

    fun isDuplicate(existing: List<ReportItem>, name: String, ts: Long) =
        existing.any { !it.sample && it.name == name && kotlin.math.abs(ts - it.ts) < DEDUP_MS }

    /** 합계에는 샘플 항목을 넣지 않는다. */
    fun monthSaved(items: List<ReportItem>, key: String, tz: TimeZone = TimeZone.getDefault()) =
        items.filter { !it.sample && !it.assumed && monthKey(it.ts, tz) == key }.sumOf { it.saved }

    /** 가정 단가로 기록된 항목 수(절약 합계에서 제외됨). */
    fun monthAssumedCount(items: List<ReportItem>, key: String, tz: TimeZone = TimeZone.getDefault()) =
        items.count { !it.sample && it.assumed && monthKey(it.ts, tz) == key }

    fun monthSpent(items: List<ReportItem>, key: String, tz: TimeZone = TimeZone.getDefault()) =
        items.filter { !it.sample && monthKey(it.ts, tz) == key }.sumOf { it.spent }

    fun monthCount(items: List<ReportItem>, key: String, tz: TimeZone = TimeZone.getDefault()) =
        items.count { !it.sample && monthKey(it.ts, tz) == key }

    fun totalSaved(items: List<ReportItem>) = items.filter { !it.sample && !it.assumed }.sumOf { it.saved }
}

/** ReportLogic 이 보는 최소 형태(app 패키지의 ReportEntry 가 구현). */
interface ReportItem {
    val ts: Long
    val name: String
    val spent: Long
    val saved: Long
    val sample: Boolean
    /** 가정 단가 기준으로 기록된 항목(지출은 실제 요금 아님, 절약액 의미 없음). */
    val assumed: Boolean get() = false
}

/** adb 시연 수신 값 검증(순수). 범위 밖/알 수 없는 값은 Ignore. */
sealed interface SimField<out T> {
    data class Set<T>(val value: T) : SimField<T>
    data object Clear : SimField<Nothing>
    data object Ignore : SimField<Nothing>
}

object SimInput {
    const val CLEAR = -1
    const val MAX_RANGE_KM = 2000f

    /** 0..100 은 설정, -1 은 해제, 그 외는 무시. */
    fun percent(v: Int): SimField<Int> = when {
        v == CLEAR -> SimField.Clear
        v in 0..100 -> SimField.Set(v)
        else -> SimField.Ignore
    }

    fun range(v: Float): SimField<Float> = when {
        v == CLEAR.toFloat() -> SimField.Clear
        v.isFinite() && v >= 0f && v <= MAX_RANGE_KM -> SimField.Set(v)
        else -> SimField.Ignore
    }

    /** adb extras 는 `--ei`/`--ef`/`--es` 어느 쪽이든 올 수 있다: Number 만 받는다(문자열·기타는 Ignore). 정수여야 하는 값에 소수는 Ignore. */
    fun percentAny(v: Any?): SimField<Int> {
        val n = v as? Number ?: return SimField.Ignore
        val d = n.toDouble()
        if (!d.isFinite() || d != Math.rint(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return SimField.Ignore
        return percent(d.toInt())
    }

    fun rangeAny(v: Any?): SimField<Float> {
        val n = v as? Number ?: return SimField.Ignore
        return range(n.toFloat())
    }

    /** 시연 모드: 이름이면 설정, CLEAR/NONE 이면 해제(저장된 설정으로 복귀), 그 외 Ignore. 메모리 전용. */
    fun modeField(s: String?): SimField<EnergyMode> {
        val u = s?.trim()?.uppercase() ?: return SimField.Ignore
        if (u == "CLEAR" || u == "NONE") return SimField.Clear
        return mode(u)?.let { SimField.Set(it) } ?: SimField.Ignore
    }

    fun mode(s: String?): EnergyMode? = s?.uppercase()?.let { u -> EnergyMode.entries.firstOrNull { it.name == u } }

    /** 시연 주행: DRIVING 만 효과가 있다. PARKED 는 '실신호로 복귀'(null) 로 해석. 그 외 값은 Ignore(null 을 반환하지 않고 Pair 로 구분). */
    fun drive(s: String?): SimField<Boolean> = when (s?.uppercase()) {
        "DRIVING" -> SimField.Set(true)
        "PARKED" -> SimField.Clear
        else -> SimField.Ignore
    }
}

/** 최소 간격(디바운스). 간격 안에 다시 들어온 입력은 거부한다. */
class MinInterval(private val minMs: Long) {
    private var last = Long.MIN_VALUE
    fun accept(nowMs: Long): Boolean {
        // nowMs < last(시계가 뒤로 감)이면 간격을 알 수 없으므로 거부하지 않고 기준을 새로 잡는다
        if (last != Long.MIN_VALUE && nowMs >= last && nowMs - last < minMs) return false
        last = nowMs; return true
    }
}

/** 키별 최소 간격(P4): 서로 다른 키의 입력은 서로를 막지 않는다. */
class KeyedInterval(private val minMs: Long) {
    private val by = HashMap<String, MinInterval>()
    fun accept(key: String, nowMs: Long): Boolean = by.getOrPut(key) { MinInterval(minMs) }.accept(nowMs)
}

/** 매니페스트에 선언된 권한만 요청한다(선언되지 않은 권한 요청 방지; 예: 제거한 POST_NOTIFICATIONS). */
object PermissionFilter {
    fun requestable(missing: List<String>, declared: Collection<String>): List<String> = missing.filter { it in declared }
}
