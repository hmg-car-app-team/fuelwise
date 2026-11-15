package com.hmgcarapp.fuelwise.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 지명이 경로선에 가리지 않게 고르는 판정. */
class MapLabelGeometryTest {
    private val box = Rect(10f, 10f, 20f, 20f)

    @Test fun segmentCrossingTheBoxHitsEvenWithBothEndsOutside() {
        assertTrue(segmentHitsRect(0f, 15f, 30f, 15f, box))
        assertTrue(segmentHitsRect(0f, 0f, 30f, 30f, box))
    }

    @Test fun segmentPassingByTheCornerMisses() {
        // 경계 상자는 겹치지만 선은 사각형 모서리 바깥을 지난다
        assertFalse(segmentHitsRect(0f, 25f, 25f, 0f, Rect(15f, 15f, 20f, 20f)))
        assertFalse(segmentHitsRect(0f, 0f, 30f, 0f, box))
    }

    @Test fun polylineHitsWhenAnySegmentDoes() {
        val line = floatArrayOf(0f, 0f, 0f, 30f, 30f, 30f)
        assertFalse(polylineHits(line, box))
        assertTrue(polylineHits(line + floatArrayOf(15f, 15f), box))
    }
}

