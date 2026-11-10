package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.domain.AlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C16(a): 동치 경계. `<=` 를 `<` 로 바꾸는 변이는 level==임계 / range==60 / 재무장 경계에서 실패한다. */
class AlertPolicyBoundaryTest {
    // 임계: 잔량 20%, 주행가능 60km
    private fun eval(level: Int?, range: Float?, armed: Boolean) = AlertPolicy.evaluate(level, range, 20, 60, armed)

    @Test fun trigger_atExactThreshold_level() {
        assertTrue("level==20", eval(20, null, true).trigger)
        assertFalse("level==21", eval(21, null, true).trigger)
        assertTrue(eval(19, null, true).trigger)
    }

    @Test fun trigger_atExactThreshold_range() {
        assertTrue("range==60", eval(null, 60f, true).trigger)
        assertFalse("range==60.1", eval(null, 60.1f, true).trigger)
        assertTrue(eval(null, 59.9f, true).trigger)
    }

    @Test fun trigger_reasonListsEverythingLow() {
        assertEquals("잔량 20%, 주행가능거리 60km", eval(20, 60f, true).reason)
    }

    @Test fun rearm_levelAtExactMargin() {
        assertTrue("level==30(20+10)", eval(30, null, false).armed)
        assertFalse("level==29", eval(29, null, false).armed)
    }

    @Test fun rearm_rangeAtExactFactor() {
        assertTrue("range==90(60*1.5)", eval(null, 90f, false).armed)
        assertFalse("range==89.9", eval(null, 89.9f, false).armed)
    }

    @Test fun rearm_requiresAllNonNullSignalsRecovered() {
        assertFalse("level 만 회복, range 낮음", eval(30, 70f, false).armed)
        assertFalse("range 만 회복, level 낮음", eval(25, 90f, false).armed)
        assertTrue(eval(30, 90f, false).armed)
    }

    @Test fun rearm_withOneNullSignal_canRearmOnTheOtherAlone_knownLimit() {
        assertTrue("level 만 있고 회복 -> 재무장(알려진 한계)", eval(30, null, false).armed)
        assertTrue("range 만 있고 회복 -> 재무장(알려진 한계)", eval(null, 90f, false).armed)
        assertFalse("모두 null 이면 상태 유지", eval(null, null, false).armed)
        assertTrue(eval(null, null, true).armed)
    }

    @Test fun rearmLevel_isCappedAt100() {
        assertEquals(100, AlertPolicy.rearmLevelPct(95))
        assertEquals(30, AlertPolicy.rearmLevelPct(20))
        assertTrue(eval(null, 90f, false).armed && !eval(100, 10f, false).armed)
    }

    @Test fun armedDecision_doesNotRetriggerUntilRearmed() {
        val first = eval(15, null, true); assertTrue(first.trigger); assertFalse(first.armed)
        val second = eval(15, null, first.armed); assertFalse(second.trigger)
    }
}
