package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.domain.CostEngine
import com.hmgcarapp.fuelwise.domain.DetourModel
import com.hmgcarapp.fuelwise.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEngineTest {
    /** 기획서 §06 예시: 40L, 리터당 30원 저렴 -> 1,200원 절약 */
    @Test fun planExample_grossSaving_is_1200() {
        assertEquals(1200L, CostEngine.grossSaving(30.0, 40.0))
    }

    /** 기획서 예시: 왕복 우회 6km, 12km/L, 1,700원/L -> 850원 소모 */
    @Test fun planExample_detourFuelCost_is_850() {
        assertEquals(850.0, CostEngine.detourFuelCost(6.0, 12.0, 1700.0), 1e-9)
    }

    // 실질 절약 350원(기획서 예시)은 Recommender.evaluate 종단 테스트(RecommenderTest.planExample_endToEnd_netSaving350)로 검증한다.

    @Test fun zeroEfficiency_doesNotDivideByZero() {
        assertEquals(0.0, CostEngine.detourFuelCost(10.0, 0.0, 1700.0), 0.0)
    }

    @Test fun haversine_gangnam_to_pangyo_about_13km() {
        val d = CostEngine.haversineKm(LatLon(37.505, 127.0499), LatLon(37.3948, 127.1112))
        assertTrue("d=$d", d in 12.0..14.5)
    }

    @Test fun detour_onRoute_isSmall_offRoute_isLarge_noDest_isRoundTrip() {
        val o = LatLon(37.505, 127.0499); val dest = LatLon(37.3948, 127.1112)
        val onRoute = DetourModel.detourKm(o, dest, LatLon(37.4772, 127.0660))
        val offRoute = DetourModel.detourKm(o, dest, LatLon(37.4960, 127.0900))
        assertTrue("on=$onRoute off=$offRoute", onRoute < 0.1 && offRoute > 1.5)
        val st = LatLon(37.4960, 127.0900)
        assertEquals(2 * CostEngine.haversineKm(o, st) * DetourModel.ROAD_FACTOR, DetourModel.detourKm(o, null, st), 1e-9)
    }
}
