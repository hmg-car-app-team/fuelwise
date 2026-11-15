package com.hmgcarapp.fuelwise.map

import com.hmgcarapp.fuelwise.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoadRouterTest {
    // A(37.5, 127.05) - B(37.5, 127.055): 골목(약 440m) 하나, 또는 C 를 거치는 고속도로(약 800m)
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
    private val c = listOf(3_750_300, 12_705_250)

    @Test fun prefersTheFasterRoadOverTheShorterOne() {
        val r = net.router.route(a, b)!!
        val pts = r.pts.toList().chunked(2)
        assertEquals(listOf(listOf(3_750_000, 12_705_000), c, listOf(3_750_000, 12_705_500)), pts)
        assertTrue("길이 ${r.meters}", r.meters in 780.0..815.0)
    }

    @Test fun givesUpWhenAnEndIsFarFromTheMainNetwork() {
        // 떨어진 도로 조각(5km 북쪽) 근처에서 출발하면 큰 연결망까지 1.5km 를 넘어 경로를 그리지 않는다
        assertNull(net.router.route(LatLon(37.55, 127.0505), a))
    }

    @Test fun viaJoinsBothLegsAtTheStop() {
        val r = net.router.via(a, LatLon(37.503, 127.0525), b)!!
        val pts = r.pts.toList().chunked(2)
        assertEquals(listOf(listOf(3_750_000, 12_705_000), c, listOf(3_750_000, 12_705_500)), pts)
        assertTrue("길이 ${r.meters}", r.meters in 780.0..815.0)
    }

    @Test fun bundledMapRoutesFromYeoksamToPangyoStationAlongRealRoads() {
        val file = listOf("src/main/assets/${MapBase.ASSET}", "fuel-wise/src/main/assets/${MapBase.ASSET}").map(::File).first { it.exists() }
        val m = MapBase.parse(file.readText())
        val r = m.router.route(LatLon(37.505, 127.0499), LatLon(37.3948, 127.1112))
        assertNotNull(r)
        // 직선거리 약 13.4km. 도로로는 그보다 길고, 두 배까지는 가지 않는다
        assertTrue("길이 ${r!!.meters}", r.meters in 13_500.0..26_000.0)
    }
}

