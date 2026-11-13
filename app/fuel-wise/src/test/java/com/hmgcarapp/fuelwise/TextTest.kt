package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.Josa
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.navi.RoutePlans
import com.hmgcarapp.fuelwise.navi.NaviReducer
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.NaviText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun josa_hangul() {
        assertEquals("알뜰주유소가", Josa.subject("알뜰주유소"))      // 받침 없음
        assertEquals("서초 충전탑이", Josa.subject("서초 충전탑"))    // 받침 있음
        assertEquals("강남은", Josa.topic("강남")); assertEquals("양재는", Josa.topic("양재"))
        assertEquals("탄천을", Josa.obj("탄천")); assertEquals("셀프를", Josa.obj("셀프"))
    }

    @Test fun josa_ignoresTrailingSymbols_andHandlesLatinAndDigits() {
        assertEquals("알뜰주유소(샘플)이", Josa.subject("알뜰주유소(샘플)")) // 플 = ㄹ 받침, 닫는 괄호는 건너뜀
        assertEquals("GS25가", Josa.subject("GS25"))    // 오 -> 받침 없음
        assertEquals("SK8이", Josa.subject("SK8"))      // 팔 -> 받침 있음
        assertEquals("주유소10을", Josa.obj("주유소10")) // 십의 0 = 영 -> 받침
        assertEquals("LG는", Josa.topic("LG"))          // g -> 지 (받침 없음)
        assertEquals("Q가", Josa.subject("Q"))
        assertFalse(Josa.hasBatchim("")); assertFalse(Josa.hasBatchim("!!!"))
    }

    /** K12(a): 약어(대문자 3자 이하)만 철자 규칙, 소문자 섞인 영어는 중립. */
    @Test fun josa_englishWords_areNeutral_onlyAcronymsUseSpelling() {
        assertTrue(Josa.isNeutral("Shell")); assertTrue(Josa.isNeutral("SK Charger")); assertTrue(Josa.isNeutral("GS Charger"))
        assertFalse(Josa.isNeutral("SK")); assertFalse(Josa.isNeutral("LG")); assertFalse(Josa.isNeutral("Q")); assertFalse(Josa.isNeutral("GS25")); assertFalse(Josa.isNeutral("강남"))
        assertTrue("4자 대문자는 약어가 아니다", Josa.isNeutral("ABCD"))
        assertEquals("Shell은(는)", Josa.topic("Shell")); assertEquals("SK Charger(이)가", Josa.subject("SK Charger")); assertEquals("Shell을(를)", Josa.obj("Shell"))
        assertEquals("LG는", Josa.topic("LG")); assertEquals("ELM은", Josa.topic("ELM")); assertEquals("SK가", Josa.subject("SK")) // M(엠) 받침 / K(케이) 없음
        assertFalse(Josa.hasBatchim("Shell")) // 추정하지 않는다
    }

    @Test fun alertText_neutralEnglishName_restructuresSentence_noGuessedJosa() {
        val q = Quote(Candidate("c", "SK Charger", "X", EnergyKind.EV, LatLon(0.0, 0.0)), 300.0, 300.0, null, 30.0, 9000, 2.0, 0, 9000, 0.0, 0.0, 0, 9000, 1350, 0, false)
        val t = AlertText.voice("잔량 15%", q, "충전", RoutePlans.ASK_VIA)
        assertTrue(t, t.contains("추천 후보는 SK Charger."))
        assertFalse(t, t.contains("SK Charger이") || t.contains("SK Charger가") || t.contains("(이)가"))
        assertTrue(t, t.contains("실질 1,350원 절약") && t.endsWith("경유지로 추가할까요?"))
    }

    @Test fun alertText_ev_negativeNetSaving_saysLowestCost_notSaving() {
        val q = Quote(Candidate("c", "양재 허브", "X", EnergyKind.EV, LatLon(0.0, 0.0)), 300.0, 300.0, null, 30.0, 9000, 4.0, 0, 9000, 0.0, 0.0, 0, 9000, -500, 0, false)
        val t = AlertText.voice("잔량 12%", q, "충전", RoutePlans.ASK_START)
        assertTrue(t, t.contains("양재 허브가 실질 비용이 가장 낮아요(우회 대략 4.0km)"))
        assertFalse(t, t.contains("절약"))
    }

    @Test fun naviErrors_areKorean_noRawCodes() {
        val codes = listOf("CAUSE_SEARCH_ROUTE", "CAUSE_SEARCH_ROUTE_OVER_DISTANCE", "CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE", "CAUSE_LOW_BATTERY", "CAUSE_SEARCH_TRIP", "UNKNOWN_X")
        codes.forEach { c ->
            val m = NaviText.errorMessage(c)
            assertFalse(m, m.contains("CAUSE") || m.contains("_") || m.startsWith("내비"))
            assertEquals("요청에 실패했어요", m) // 원인별 사유 없이 한 문장으로 통일
        }
    }

    @Test fun naviReducer_onlyChangesWhenPending() {
        val idle = NaviStatus.Idle
        assertSame(idle, NaviReducer.onSuccess(idle, "x"))
        assertSame(idle, NaviReducer.onError(idle, "CAUSE_SEARCH_ROUTE"))
        assertSame(idle, NaviReducer.onTimeout(idle))
        val done = NaviStatus.Success("끝")
        assertSame(done, NaviReducer.onError(done, "CAUSE_SEARCH_ROUTE")) // 관계없는 늦은 오류 무시
        val pending = NaviStatus.Pending("요청")
        assertEquals(NaviStatus.Success(NaviText.WAYPOINT_ADDED), NaviReducer.onSuccess(pending, NaviText.WAYPOINT_ADDED))
        assertEquals(NaviStatus.Failed("요청에 실패했어요"), NaviReducer.onError(pending, "CAUSE_SEARCH_ROUTE"))
        assertEquals(NaviStatus.Failed(NaviText.NO_RESPONSE), NaviReducer.onTimeout(pending))
    }

    @org.junit.Test fun detailEmptyDoesNotRepeatNoCandidateNotice() {
        org.junit.Assert.assertEquals("후보를 선택하세요.", com.hmgcarapp.fuelwise.ui.detailEmptyText(true))
        org.junit.Assert.assertEquals("후보를 선택하세요.", com.hmgcarapp.fuelwise.ui.detailEmptyText(false))
        org.junit.Assert.assertFalse(com.hmgcarapp.fuelwise.ui.detailEmptyText(true).contains("후보가 없어요"))
    }
}
