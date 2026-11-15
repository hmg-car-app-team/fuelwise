package com.hmgcarapp.fuelwise.map

import com.hmgcarapp.fuelwise.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadDetourTest {
    // RoadRouterTest 와 같은 작은 도로망: A-B 골목, A-C-B 고속도로(가장 빠른 길), 북쪽에 떨어진 조각
    private val net = MapBase.parse(
        """
        R6 50000,25000,0,500
        R1 50000,25000,300,250
        R1 50300,25250,-300,250
        R3 55000,25000,0,100
        """.trimIndent(),
    )
    private val a = LatLon(37.5, 127.05)
    private val b = LatLon(37.5, 127.055)
    private val c = LatLon(37.503, 127.0525)

    @Test fun withoutDestinationItIsTheRoundTripAlongTheFastestRoad() {
        // A-C 고속도로 약 398m 를 왕복
        assertEquals(0.797, RoadDetour(net.router).detourKm(a, null, c)!!, 0.01)
    }

    @Test fun aStationOnTheFastestRouteToTheDestinationAddsNothing() {
        assertEquals(0.0, RoadDetour(net.router).detourKm(a, b, c)!!, 0.01)
    }

    @Test fun pointsFarFromTheRoadsFallBackToStraightLine() {
        val d = RoadDetour(net.router)
        assertNull(d.detourKm(a, null, LatLon(37.6, 127.2)))
        assertNull(d.detourKm(LatLon(37.6, 127.2), null, c))
    }
}

