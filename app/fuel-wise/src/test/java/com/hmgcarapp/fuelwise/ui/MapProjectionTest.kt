package com.hmgcarapp.fuelwise.ui

import com.hmgcarapp.fuelwise.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 지도 투영과 축척: 패널이 가리는 영역을 피해 모든 점이 화면 안에 들어오는지. */
class MapProjectionTest {
    private val w = 1700f
    private val h = 1184f

    @Test fun fit_keepsEveryPointInsideSafeArea() {
        val pts = listOf(
            LatLon(37.505, 127.0499), LatLon(37.496, 127.090), LatLon(37.460, 127.090),
            LatLon(37.3948, 127.1112), LatLon(37.505, 127.030),
        )
        val p = MapProjection.fit(pts, w, h, left = 632f, top = 96f, right = 16f, bottom = 16f)
        pts.forEach {
            val o = p.toOffset(it)
            assertTrue("x=${o.x}", o.x in 632f..(w - 16f))
            assertTrue("y=${o.y}", o.y in 96f..(h - 16f))
        }
    }

    @Test fun fit_singlePoint_isCenteredWithCappedZoom() {
        val only = LatLon(37.5, 127.0)
        val p = MapProjection.fit(listOf(only), w, h, 0f, 0f, 0f, 0f)
        val o = p.toOffset(only)
        assertEquals(w / 2, o.x, 0.01f)
        assertEquals(h / 2, o.y, 0.01f)
        assertEquals((h / MapProjection.MIN_SPAN_KM).toFloat(), p.pxPerKm, 0.01f)
    }

    @Test fun fit_duplicatePoints_behaveLikeOnePoint() {
        val a = LatLon(37.5, 127.0)
        assertEquals(MapProjection.fit(listOf(a), w, h, 0f, 0f, 0f, 0f), MapProjection.fit(listOf(a, a, a), w, h, 0f, 0f, 0f, 0f))
    }

    @Test fun scaleBar_picksLongestNiceDistanceThatFits() {
        assertEquals(1.0, scaleBarKm(pxPerKm = 100f, maxPx = 120f), 0.0)
        assertEquals(0.2, scaleBarKm(pxPerKm = 5000f, maxPx = 120f), 0.0)
        assertEquals("500m", scaleLabel(0.5))
        assertEquals("2km", scaleLabel(2.0))
    }
}
