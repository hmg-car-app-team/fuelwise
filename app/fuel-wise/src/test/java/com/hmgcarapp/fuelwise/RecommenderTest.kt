package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.data.SampleJsonParser
import com.hmgcarapp.fuelwise.domain.AlertPolicy
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.CostEngine
import com.hmgcarapp.fuelwise.domain.DetourModel
import com.hmgcarapp.fuelwise.domain.Discount
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EvalInput
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Presets
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.domain.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecommenderTest {
    private val origin = LatLon(37.505, 127.0499)
    private fun sample(): List<Candidate> {
        val f = listOf("src/main/assets/stations_sample.json", "fuel-wise/src/main/assets/stations_sample.json").map(::File).first { it.exists() }
        return SampleJsonParser.parse(f.readText())
    }

    @Test fun sampleJson_parses_fuel_and_ev() {
        val c = sample()
        assertEquals(10, c.count { it.kind == EnergyKind.FUEL })
        assertEquals(5, c.count { it.kind == EnergyKind.EV })
        assertTrue(c.filter { it.kind == EnergyKind.FUEL }.all { it.fuelPrices.size == 4 })
    }

    /** 같은 위치(우회 동일) 두 주유소, 가격차 30원 x 40L, 우회 0 -> 실질 절약 정확히 1,200원 */
    @Test fun twoStations_samePlace_saving1200_exact() {
        val pos = LatLon(37.49, 127.06)
        val a = Candidate("a", "A", "X", EnergyKind.FUEL, pos, mapOf(FuelGrade.GASOLINE to 1700))
        val b = Candidate("b", "B", "X", EnergyKind.FUEL, pos, mapOf(FuelGrade.GASOLINE to 1670))
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, Settings(tankL = 40), emptyList(), 40.0), listOf(a, b))
        val qb = q.first { it.candidate.id == "b" }
        assertEquals(1200L, qb.netSaving)
        assertEquals(1200L, qb.grossSaving)
        assertEquals("b", Recommender.best(q)!!.candidate.id)
        assertTrue(q.first { it.candidate.id == "a" }.isBaseline) // 동률 우회 -> 표시 단가가 높은 쪽이 기준
    }

    /** 기획서 예시 종단: 40L, 기준 1,700원/L, 후보 1,670원/L, 왕복 6km, 12km/L -> 실질 절약 정확히 350원 */
    @Test fun planExample_endToEnd_netSaving350() {
        val base = Candidate("base", "기준", "X", EnergyKind.FUEL, origin, mapOf(FuelGrade.GASOLINE to 1700))
        val d = 6.0 / 2 / DetourModel.ROAD_FACTOR // 왕복 x1.3 = 6km 가 되는 직선거리(km)
        val cand = Candidate("cand", "후보", "X", EnergyKind.FUEL, LatLon(origin.lat + d / 111.19492664455873, origin.lon), mapOf(FuelGrade.GASOLINE to 1670))
        val s = Settings(tankL = 40, kmPerL = 12.0)
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, s, emptyList(), 40.0), listOf(cand, base))
        val c = q.first { it.candidate.id == "cand" }
        assertEquals(6.0, c.detourKm, 1e-3)
        assertTrue(q.first { it.candidate.id == "base" }.isBaseline)
        assertEquals(1200L, c.grossSaving)
        assertEquals(850L, c.detourEnergyCost)
        assertEquals(350L, c.netSaving)
    }

    /** 우회 에너지비 단가는 기준 후보의 표시 단가(할인 전) — 할인이 있어도 바뀌지 않는다 */
    @Test fun detourCost_usesBaselineListPrice_notDiscounted() {
        val base = Candidate("base", "기준", "A정유", EnergyKind.FUEL, origin, mapOf(FuelGrade.GASOLINE to 1700))
        val d = 6.0 / 2 / DetourModel.ROAD_FACTOR
        val cand = Candidate("cand", "후보", "B", EnergyKind.FUEL, LatLon(origin.lat + d / 111.19492664455873, origin.lon), mapOf(FuelGrade.GASOLINE to 1670))
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, Settings(tankL = 40), Presets.discounts, 40.0), listOf(base, cand))
        assertEquals(850L, q.first { it.candidate.id == "cand" }.detourEnergyCost)
    }

    @Test fun discount_reducesUnitPrice_bestSingleDiscountApplied() {
        val c = sample().filter { it.kind == EnergyKind.FUEL }
        val s = Settings()
        val none = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, s, emptyList(), 30.0), c).first { it.candidate.id == "f01" }
        val cards = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, s, Presets.discounts, 30.0), c).first { it.candidate.id == "f01" }
        assertEquals(none.unitPrice - 60.0, cards.unitPrice, 1e-9) // A정유 카드(-60) > 전브랜드(-20), 중복 적용 안 함
        assertNotNull(cards.discountName)
    }

    @Test fun exactlyOneBaseline_isMinDetour_withZeroSaving() {
        val c = sample().filter { it.kind == EnergyKind.FUEL }
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, Settings(), emptyList(), 20.0), c)
        assertEquals(1, q.count { it.isBaseline })
        val base = q.first { it.isBaseline }
        assertEquals(0L, base.netSaving)
        assertEquals(q.minOf { it.detourKm }, base.detourKm, 0.0) // 기준 = 우회 최소
    }

    @Test fun sortKeys_orderByScore_price_distance() {
        val c = sample().filter { it.kind == EnergyKind.FUEL }
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, Settings(), emptyList(), 30.0), c)
        assertEquals(q.sortedBy { it.score }.map { it.candidate.id }, Recommender.sort(q, SortKey.COST).map { it.candidate.id })
        assertEquals(q.minOf { it.unitPrice }, Recommender.sort(q, SortKey.PRICE).first().unitPrice, 0.0)
        assertEquals(q.minOf { it.detourKm }, Recommender.sort(q, SortKey.DISTANCE).first().detourKm, 0.0)
        assertEquals("f08", Recommender.sort(q, SortKey.PRICE).first().candidate.id) // 샘플 최저가 1,629원
        assertEquals(q.maxOf { it.unitPrice }, Recommender.sort(q, SortKey.PRICE).last().unitPrice, 0.0)
    }

    @Test fun emptyCandidates_andMissingGradePrice_andNullCharger_areDropped() {
        val input = EvalInput(origin, null, EnergyKind.FUEL, Settings(), emptyList(), 30.0)
        assertTrue(Recommender.evaluate(input, emptyList()).isEmpty())
        assertNull(Recommender.best(emptyList()))
        val noGrade = Candidate("n", "N", "X", EnergyKind.FUEL, origin, mapOf(FuelGrade.DIESEL to 1500))
        val ok = Candidate("o", "O", "X", EnergyKind.FUEL, origin, mapOf(FuelGrade.GASOLINE to 1700))
        assertEquals(listOf("o"), Recommender.evaluate(input, listOf(noGrade, ok)).map { it.candidate.id })
        val noKw = Candidate("k", "K", "X", EnergyKind.EV, origin, pricePerKwh = 300, chargerKw = null)
        val kw = Candidate("w", "W", "X", EnergyKind.EV, origin, pricePerKwh = 300, chargerKw = 100.0)
        val ev = Recommender.evaluate(EvalInput(origin, null, EnergyKind.EV, Settings(), emptyList(), 20.0), listOf(noKw, kw))
        assertEquals(listOf("w"), ev.map { it.candidate.id })
    }

    @Test fun invalidEfficiency_usesSafeDefault_notZeroDetourCost() {
        val base = Candidate("base", "기준", "X", EnergyKind.FUEL, origin, mapOf(FuelGrade.GASOLINE to 1700))
        val far = Candidate("far", "먼곳", "X", EnergyKind.FUEL, LatLon(origin.lat + 0.02, origin.lon), mapOf(FuelGrade.GASOLINE to 1600))
        for (bad in listOf(0.0, -3.0, Double.NaN)) {
            val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, Settings(kmPerL = bad), emptyList(), 30.0), listOf(base, far))
            assertTrue("kmPerL=$bad", q.first { it.candidate.id == "far" }.detourEnergyCost > 0)
        }
    }

    @Test fun quantity_chargeTargetBelowLevel_isZero_andFuelBounds() {
        assertEquals(0.0, Recommender.quantityFor(EnergyKind.EV, 90, Settings(chargeTargetPct = 80)), 0.0)
        assertEquals(0.0, Recommender.quantityFor(EnergyKind.FUEL, 100, Settings()), 0.0)
        assertEquals(55.0, Recommender.quantityFor(EnergyKind.FUEL, 0, Settings(tankL = 55)), 1e-9)
        assertEquals(55.0, Recommender.quantityFor(EnergyKind.FUEL, -10, Settings(tankL = 55)), 1e-9) // 범위 밖 잔량은 보정
    }

    @Test fun nearby_filtersByRadius20km() {
        val perDeg = 111.19492664455873
        val inside = Candidate("in", "in", "X", EnergyKind.FUEL, LatLon(origin.lat + 19.9 / perDeg, origin.lon))
        val outside = Candidate("out", "out", "X", EnergyKind.FUEL, LatLon(origin.lat + 20.5 / perDeg, origin.lon))
        assertEquals(20.0, Recommender.CANDIDATE_RADIUS_KM, 0.0)
        assertEquals(listOf("in"), Recommender.nearby(listOf(inside, outside), origin).map { it.id })
        assertTrue(Recommender.nearby(sample(), LatLon(35.1796, 129.0756)).isEmpty()) // 부산에서는 강남 샘플이 모두 반경 밖
        assertEquals(sample().size, Recommender.nearby(sample(), origin).size)
    }

    @Test fun ev_timeAndOutputAffectsRanking() {
        val c = sample().filter { it.kind == EnergyKind.EV }
        val kwh = Recommender.quantityFor(EnergyKind.EV, 20, Settings())
        assertEquals(77.4 * 0.60, kwh, 1e-6)
        val noTime = Recommender.evaluate(EvalInput(origin, Presets.destinations[0].pos, EnergyKind.EV, Settings(evTimeValueWonPerMin = 0), emptyList(), kwh), c)
        val time = Recommender.evaluate(EvalInput(origin, Presets.destinations[0].pos, EnergyKind.EV, Settings(evTimeValueWonPerMin = 100), emptyList(), kwh), c)
        // 시간 가치 0 이면 시간 비용 0, 100원/분이면 (이동+대기+충전 분) x 100 이 점수에 그대로 더해진다 - 점수 값을 직접 비교
        val a0 = noTime.first { it.candidate.id == "e02" }; val a1 = time.first { it.candidate.id == "e02" }
        assertEquals(0L, a0.timeCost); assertTrue(a1.timeCost > 0)
        assertEquals(Math.round((a1.extraMin + a1.chargeMin) * 100.0), a1.timeCost)
        assertEquals(a0.totalCost, a1.totalCost)               // 에너지 비용은 같고
        assertEquals(a0.score + a1.timeCost, a1.score)         // 점수만 시간 비용만큼 오른다
        val fast = time.first { it.candidate.id == "e05" }      // 350kW(차량 한도 150kW) 는 50kW 허브보다 충전이 빠르다
        assertTrue("50kW=${a1.chargeMin} fast=${fast.chargeMin}", a1.chargeMin > fast.chargeMin)
        // 차량 한도 50kW 면 350kW 충전소도 50kW 로 계산
        val capped = Recommender.evaluate(EvalInput(origin, null, EnergyKind.EV, Settings(evMaxKw = 50), emptyList(), 30.0), c).first { it.candidate.id == "e05" }
        assertEquals(30.0 / 50.0 * 60, capped.chargeMin, 1e-9)
    }

    @Test fun ev_discount_percent() {
        val c = sample().filter { it.kind == EnergyKind.EV }
        val d = listOf(Discount("m", "m", evPercent = 10))
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.EV, Settings(), d, 20.0), c).first { it.candidate.id == "e02" }
        assertEquals(292 * 0.9, q.unitPrice, 1e-9)
    }

    @Test fun alert_triggersOnce_thenRearmsAfterRecovery() {
        var d = AlertPolicy.evaluate(25, null, 20, 60, true); assertFalse(d.trigger)
        d = AlertPolicy.evaluate(19, null, 20, 60, true); assertTrue(d.trigger); assertFalse(d.armed)
        d = AlertPolicy.evaluate(15, null, 20, 60, false); assertFalse(d.trigger); assertFalse(d.armed)   // 재알림 없음
        d = AlertPolicy.evaluate(25, null, 20, 60, false); assertFalse(d.armed)                          // 히스테리시스 미달
        d = AlertPolicy.evaluate(80, null, 20, 60, false); assertTrue(d.armed)                           // 주유 후 재무장
    }

    @Test fun alert_nullSignal_isNotTreatedAsRecovered() {
        // 잔량 15%/주행가능 40km 로 알림 -> armed=false
        var armed = AlertPolicy.evaluate(15, 40f, 20, 60, true).also { assertTrue(it.trigger) }.armed
        assertFalse(armed)
        // 잔량 신호가 사라져도(null) 주행가능거리가 여전히 낮으면 재무장하지 않는다
        armed = AlertPolicy.evaluate(null, 40f, 20, 60, armed).armed; assertFalse(armed)
        // 잔량 값이 다시 낮게 돌아와도 중복 알림이 없다
        val back = AlertPolicy.evaluate(15, 40f, 20, 60, armed); assertFalse(back.trigger); assertFalse(back.armed)
        // 모든 신호가 null 이면 이전 상태(armed=false) 유지
        assertFalse(AlertPolicy.evaluate(null, null, 20, 60, false).armed)
        assertTrue(AlertPolicy.evaluate(null, null, 20, 60, true).armed)
        assertFalse(AlertPolicy.evaluate(null, null, 20, 60, true).trigger)
    }

    @Test fun alert_rearmMargin_isCappedAt100() {
        assertEquals(30, AlertPolicy.rearmLevelPct(20))
        assertEquals(100, AlertPolicy.rearmLevelPct(95))
        assertTrue(AlertPolicy.evaluate(100, null, 95, 60, false).armed) // 임계 95% 라도 100% 면 재무장 가능
        assertFalse(AlertPolicy.evaluate(99, null, 95, 60, false).armed)
    }

    @Test fun alert_rearm_requiresAllActiveSignalsRecovered() {
        assertFalse(AlertPolicy.evaluate(80, 70f, 20, 60, false).armed)  // 거리 70 < 90
        assertTrue(AlertPolicy.evaluate(80, 100f, 20, 60, false).armed)
        assertTrue(AlertPolicy.evaluate(80, null, 20, 60, false).armed)   // 활성 신호(잔량)가 모두 회복
    }

    @Test fun alert_byRangeOnly_andNoSignals() {
        assertTrue(AlertPolicy.evaluate(null, 40f, 20, 60, true).trigger)
        assertFalse(AlertPolicy.evaluate(null, null, 20, 60, true).trigger)
        assertFalse(AlertPolicy.evaluate(null, 200f, 20, 60, true).trigger)
    }

    /** 목적지가 있는 종단: 경로 위 후보는 우회가 거의 없어 기준, 경로 밖 싼 후보는 우회비를 빼고 절약을 계산한다. */
    @Test fun withDestination_endToEnd() {
        val dest = Presets.destinations[0].pos // 판교역
        val onRoute = Candidate("on", "경로상", "X", EnergyKind.FUEL, LatLon(37.4772, 127.0660), mapOf(FuelGrade.GASOLINE to 1700))
        val off = Candidate("off", "경로밖", "X", EnergyKind.FUEL, LatLon(37.4960, 127.0900), mapOf(FuelGrade.GASOLINE to 1650))
        val q = Recommender.evaluate(EvalInput(origin, dest, EnergyKind.FUEL, Settings(tankL = 40, kmPerL = 12.0), emptyList(), 40.0), listOf(onRoute, off))
        val qon = q.first { it.candidate.id == "on" }; val qoff = q.first { it.candidate.id == "off" }
        assertTrue(qon.isBaseline); assertTrue("on=${qon.detourKm}", qon.detourKm < 0.1); assertTrue("off=${qoff.detourKm}", qoff.detourKm > 1.5)
        assertEquals(2000L, qoff.grossSaving) // (1700-1650) x 40L
        val detourCostOff = Math.round(CostEngine.detourFuelCost(qoff.detourKm, 12.0, 1700.0)) // 우회비는 기준 후보 표시 단가
        val detourCostOn = Math.round(CostEngine.detourFuelCost(qon.detourKm, 12.0, 1700.0))
        assertEquals(2000L - (detourCostOff - detourCostOn), qoff.netSaving)
        assertEquals("off", Recommender.best(q)!!.candidate.id)
    }

    /** EV 실질 절약: 같은 위치(우회 0)의 두 충전소, 30kWh x 50원 차이 = 정확히 1,500원. */
    @Test fun ev_netSaving_exact() {
        val pos = LatLon(37.49, 127.06)
        val a = Candidate("a", "A", "X", EnergyKind.EV, pos, pricePerKwh = 300, chargerKw = 100.0)
        val b = Candidate("b", "B", "X", EnergyKind.EV, pos, pricePerKwh = 250, chargerKw = 100.0, waitMin = 20)
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.EV, Settings(), emptyList(), 30.0), listOf(a, b))
        assertTrue(q.first { it.candidate.id == "a" }.isBaseline) // 우회 동률이면 표시 단가가 높은 쪽이 기준
        assertEquals(1500L, q.first { it.candidate.id == "b" }.netSaving) // 대기 시간은 시간 비용(점수)에만 반영, 순절약(원)에는 반영 안 함
        assertTrue(q.first { it.candidate.id == "b" }.timeCost > q.first { it.candidate.id == "a" }.timeCost)
    }
}
