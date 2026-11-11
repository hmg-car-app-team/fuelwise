package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.vehicle.SignalStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SignalStatusTextTest {
    private val raw = "java.lang.IllegalArgumentException: property ID is not in carPropertyConfig list, and so it is not supported: 0x21400be5"

    @Test fun releaseHidesRawDiagnostics() {
        val texts = listOf(raw, "SDK 불가: java.lang.IllegalStateException: boom", "응답 없음(5초)", "값 없음(null)", null)
            .map { SignalStatusText.missing(it, debuggable = false) }
        texts.forEach { t ->
            assertFalse(t, t.contains("Exception") || t.contains("0x") || t.contains("java.") || t.contains("property ID"))
        }
    }

    @Test fun releaseTextPerStatus() {
        assertEquals("이 차량에서는 제공되지 않아요", SignalStatusText.missing(raw, false))
        assertEquals("값이 아직 없어요", SignalStatusText.missing("값 없음(null)", false))
        assertEquals("값이 아직 없어요", SignalStatusText.missing("0 (값 없음)", false))
        assertEquals("값이 아직 없어요", SignalStatusText.missing(null, false))
        assertEquals("읽지 못했어요", SignalStatusText.missing("응답 없음(5초)", false))
        assertEquals("읽지 못했어요", SignalStatusText.missing("SDK 불가: x", false))
    }

    @Test fun debuggableKeepsRaw() {
        assertEquals("없음: $raw", SignalStatusText.missing(raw, true))
        assertEquals("없음: 대기", SignalStatusText.missing(null, true))
    }
}
