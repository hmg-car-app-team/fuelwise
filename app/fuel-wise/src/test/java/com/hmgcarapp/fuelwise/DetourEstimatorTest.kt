package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.DetourEstimator
import com.hmgcarapp.fuelwise.domain.DetourModel
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EvalInput
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** 우회 거리 어림(도로망)을 쓰되, 어림이 없는 후보는 직선 근사로 계산한다. */
class DetourEstimatorTest {
    private val origin = LatLon(37.505, 127.0499)
    private fun fuel(id: String, lat: Double, lon: Double) =
        Candidate(id, id, "브랜드", EnergyKind.FUEL, LatLon(lat, lon), fuelPrices = FuelGrade.entries.associateWith { 1600 })

    @Test fun estimatedDetourIsUsedAndMissingOnesFallBack() {
        val onMap = fuel("on", 37.51, 127.06)
        val offMap = fuel("off", 37.52, 127.07)
        val est = DetourEstimator { _, _, s -> if (s == onMap.pos) 4.2 else null }
        val q = Recommender.evaluate(EvalInput(origin, null, EnergyKind.FUEL, Settings(), emptyList(), 30.0, est), listOf(onMap, offMap))
        assertEquals(4.2, q.first { it.candidate.id == "on" }.detourKm, 1e-9)
        assertEquals(DetourModel.detourKm(origin, null, offMap.pos), q.first { it.candidate.id == "off" }.detourKm, 1e-9)
    }
}

