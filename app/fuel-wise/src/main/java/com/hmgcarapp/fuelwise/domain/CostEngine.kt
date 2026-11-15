package com.hmgcarapp.fuelwise.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** 비용 계산 순수 함수 모음. 총비용 = 단가 x 주입량 + 우회 에너지비. */
object CostEngine {
    /** 우회로 소모되는 연료비(원). 예: 6km / 12km/L x 1,700원 = 850원 */
    fun detourFuelCost(detourKm: Double, kmPerL: Double, pricePerL: Double): Double =
        if (kmPerL <= 0.0) 0.0 else detourKm / kmPerL * pricePerL

    fun fillCost(unitPrice: Double, quantity: Double): Double = unitPrice * quantity

    fun totalCost(unitPrice: Double, quantity: Double, detourEnergyCost: Double): Double = fillCost(unitPrice, quantity) + detourEnergyCost

    /** 기준(baseline) 총비용 대비 실질 절약(원). 음수면 오히려 손해. */
    fun netSaving(baselineTotal: Double, candidateTotal: Double): Long = (baselineTotal - candidateTotal).roundToLong()

    /** 가격 차이만으로 본 절약(우회 미반영). 기획서: 40L x 30원 = 1,200원 */
    fun grossSaving(priceDiffPerUnit: Double, quantity: Double): Long = (priceDiffPerUnit * quantity).roundToLong()

    fun haversineKm(a: LatLon, b: LatLon): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}

/** 직선거리 -> 도로거리 보정 계수와 우회 이동 속도(근사). */
object DetourModel {
    const val ROAD_FACTOR = 1.3
    const val DETOUR_SPEED_KMH = 40.0

    /**
     * 우회 거리(km). 목적지가 있으면 경유 추가 거리 = d(현재,후보)+d(후보,목적지)-d(현재,목적지),
     * 없으면 현재 위치 기준 왕복 2 x d(현재,후보). 모두 직선거리 x 도로계수 근사(실제 경로 아님).
     */
    fun detourKm(origin: LatLon, dest: LatLon?, station: LatLon): Double {
        val oa = CostEngine.haversineKm(origin, station)
        val raw = if (dest == null) 2 * oa else max(0.0, oa + CostEngine.haversineKm(station, dest) - CostEngine.haversineKm(origin, dest))
        return raw * ROAD_FACTOR
    }
}

/**
 * 우회 거리(km)를 따로 어림하는 방법(예: 앱에 넣은 지도의 도로망). 정의는 [DetourModel] 과 같다(목적지가 없으면 왕복).
 * 후보마다 null 을 돌려주면(지도 밖·도로에서 먼 곳) 그 후보는 [DetourModel] 직선 근사로 계산한다.
 */
fun interface DetourEstimator {
    fun detourKm(origin: LatLon, dest: LatLon?, station: LatLon): Double?
}

data class Quote(
    val candidate: Candidate,
    val listUnitPrice: Double,
    val unitPrice: Double,
    val discountName: String?,
    val quantity: Double,
    val fillCost: Long,
    val detourKm: Double,
    val detourEnergyCost: Long,
    val totalCost: Long,
    val extraMin: Double,
    val chargeMin: Double,
    val timeCost: Long,
    val score: Long,
    /** 기준 후보 대비 실질 절약(우회 연료비 반영). 기준 자신은 0 */
    val netSaving: Long,
    /** 기준 후보 대비 가격만의 절약(우회 미반영) */
    val grossSaving: Long,
    val isBaseline: Boolean = false,
    /** 기준 또는 이 후보 중 하나라도 가정 단가면 false: 절약(netSaving/grossSaving)은 0 으로 두고 표기·음성·배너에서 쓰지 않는다. */
    val savingComparable: Boolean = true,
)

data class EvalInput(
    val origin: LatLon,
    val dest: LatLon?,
    val kind: EnergyKind,
    val settings: Settings,
    val discounts: List<Discount>,
    /** 주입량: 주유 L / 충전 kWh */
    val quantity: Double,
    /** 우회 거리 어림(도로망). null 이면 모든 후보를 직선 근사로 계산한다. */
    val detour: DetourEstimator? = null,
)

object Recommender {
    /** 후보 반경(현재 위치 기준 직선거리). 이보다 먼 후보는 비교에서 제외한다. */
    const val CANDIDATE_RADIUS_KM = 20.0

    /** 현재 위치에서 [radiusKm] 이내(경계 포함) 후보만. */
    fun nearby(candidates: List<Candidate>, origin: LatLon, radiusKm: Double = CANDIDATE_RADIUS_KM): List<Candidate> =
        candidates.filter { CostEngine.haversineKm(origin, it.pos) <= radiusKm }

    /** 목적지 방향 후보 탐색 거리(현재 위치 기준 직선거리). */
    const val ROUTE_LOOKAHEAD_KM = 60.0

    /** 목적지 방향 후보로 인정하는 우회 상한(km, 직선 근사 x 도로계수 적용 후). */
    const val DETOUR_LIMIT_KM = 3.0

    /**
     * 비교 후보 범위. 목적지가 없으면 [nearby] 와 같다. 있으면 기존 반경 안 후보는 그대로 두고(기존 동작 보존),
     * 반경 밖이라도 [ROUTE_LOOKAHEAD_KM] 이내이면서 우회가 [DETOUR_LIMIT_KM] 이하인 목적지 방향 후보를 더한다.
     * [rangeKm] 이 있으면 확장 후보는 현재 위치에서 그 거리 이내(경계 포함)여야 한다(도달 불가 후보 배제).
     */
    fun candidatesFor(candidates: List<Candidate>, origin: LatLon, dest: LatLon?, rangeKm: Double? = null): List<Candidate> =
        if (dest == null) nearby(candidates, origin)
        else candidates.filter { c ->
            val oa = CostEngine.haversineKm(origin, c.pos)
            oa <= CANDIDATE_RADIUS_KM || (oa <= ROUTE_LOOKAHEAD_KM && (rangeKm == null || oa <= rangeKm) && DetourModel.detourKm(origin, dest, c.pos) <= DETOUR_LIMIT_KM)
        }

    fun quantityFor(kind: EnergyKind, levelPct: Int, s: Settings): Double = when (kind) {
        EnergyKind.FUEL -> s.tankL * (100 - levelPct).coerceIn(0, 100) / 100.0
        EnergyKind.EV -> s.batteryKwh * (s.chargeTargetPct - levelPct).coerceAtLeast(0) / 100.0
    }

    private fun bestDiscount(c: Candidate, ds: List<Discount>): Discount? = ds.filter { it.appliesTo(c) }.maxByOrNull {
        if (c.kind == EnergyKind.FUEL) it.wonPerLiter.toDouble() else (c.pricePerKwh ?: 0) * it.evPercent / 100.0
    }?.takeIf { if (c.kind == EnergyKind.FUEL) it.wonPerLiter > 0 else it.evPercent > 0 }

    /** 효율이 0 이하/NaN 이면 기본값으로 대체(우회비가 0 으로 사라지는 것을 방지). */
    private fun safeEfficiency(v: Double, default: Double) = if (v.isFinite() && v > 0.0) v else default

    private class Raw(
        val c: Candidate, val listPrice: Double, val unit: Double, val discountName: String?,
        val detourKm: Double, val extraMin: Double, val chargeMin: Double,
    )

    /**
     * 단가 정책: 우회 에너지비는 **기준 후보(우회가 가장 적은 후보)의 표시 단가(할인 전)**로 평가한다
     * (기준이 없으면 후보 자신의 표시 단가). 주입 비용은 각 후보의 할인 적용 단가.
     * 비용은 표시되는 정수(원)끼리 계산한다: 총비용 = 주입비 + 우회비, 절약 = 기준 총비용 - 후보 총비용.
     */
    fun evaluate(input: EvalInput, candidates: List<Candidate>): List<Quote> {
        val s = input.settings
        val effFuel = safeEfficiency(s.kmPerL, Settings().kmPerL)
        val effEv = safeEfficiency(s.kmPerKwh, Settings().kmPerKwh)
        val raw = candidates.filter { it.kind == input.kind }.mapNotNull { c ->
            val listPrice = when (c.kind) {
                EnergyKind.FUEL -> c.fuelPrices[s.fuelGrade]?.toDouble()
                EnergyKind.EV -> c.pricePerKwh?.toDouble()
            }?.takeIf { it > 0.0 } ?: return@mapNotNull null
            // 충전기 출력을 모르면 충전 시간을 추정할 수 없으므로 후보에서 제외
            val kw = if (c.kind == EnergyKind.EV) min(c.chargerKw ?: 0.0, s.evMaxKw.toDouble()).takeIf { it > 0.0 } ?: return@mapNotNull null else 0.0
            val d = bestDiscount(c, input.discounts)
            val unit = when {
                d == null -> listPrice
                c.kind == EnergyKind.FUEL -> listPrice - d.wonPerLiter
                else -> listPrice * (100 - d.evPercent) / 100.0
            }
            val detour = input.detour?.detourKm(input.origin, input.dest, c.pos)?.takeIf { it.isFinite() && it >= 0.0 }
                ?: DetourModel.detourKm(input.origin, input.dest, c.pos)
            val extraMin = detour / DetourModel.DETOUR_SPEED_KMH * 60 + c.waitMin
            val chargeMin = if (c.kind == EnergyKind.EV) input.quantity / kw * 60 else 0.0
            Raw(c, listPrice, unit, d?.name, detour, extraMin, chargeMin)
        }
        if (raw.isEmpty()) return emptyList()
        // 기준 = 우회가 가장 적은 후보(= "가장 가까운 곳에서 그냥 넣는" 선택). 동률이면 표시 단가가 높은 쪽(보수적), 그다음 id.
        val base = raw.minWith(compareBy<Raw>({ it.detourKm }).thenByDescending { it.listPrice }.thenBy { it.c.id })
        val eff = { r: Raw -> if (r.c.kind == EnergyKind.FUEL) effFuel else effEv }
        fun detourCostOf(r: Raw) = CostEngine.detourFuelCost(r.detourKm, eff(r), base.listPrice).roundToLong()
        fun fillOf(r: Raw) = (r.unit * input.quantity).roundToLong()
        val baseTotal = fillOf(base) + detourCostOf(base)
        val baseFill = fillOf(base)
        return raw.map { r ->
            val fill = fillOf(r)
            val detourCost = detourCostOf(r)
            val total = fill + detourCost
            val timeCost = if (r.c.kind == EnergyKind.EV) ((r.extraMin + r.chargeMin) * s.evTimeValueWonPerMin).roundToLong() else 0L
            val isBase = r === base
            val comparable = isBase || !(base.c.priceAssumed || r.c.priceAssumed)
            Quote(
                candidate = r.c, listUnitPrice = r.listPrice, unitPrice = r.unit, discountName = r.discountName, quantity = input.quantity,
                fillCost = fill, detourKm = r.detourKm, detourEnergyCost = detourCost, totalCost = total,
                extraMin = r.extraMin, chargeMin = r.chargeMin, timeCost = timeCost, score = total + timeCost,
                netSaving = if (isBase || !comparable) 0L else baseTotal - total,
                grossSaving = if (isBase || !comparable) 0L else baseFill - fill,
                isBaseline = isBase, savingComparable = comparable,
            )
        }
    }

    fun sort(quotes: List<Quote>, key: SortKey): List<Quote> = when (key) {
        SortKey.COST -> quotes.sortedBy { it.score }
        SortKey.PRICE -> quotes.sortedWith(compareBy<Quote>({ it.unitPrice }, { it.detourKm }, { it.candidate.id }))
        SortKey.DISTANCE -> quotes.sortedBy { it.detourKm }
    }

    /** 추천 = 실질 비용(EV 는 시간 환산 포함)이 가장 낮은 후보. */
    fun best(quotes: List<Quote>): Quote? = quotes.minByOrNull { it.score }
}
