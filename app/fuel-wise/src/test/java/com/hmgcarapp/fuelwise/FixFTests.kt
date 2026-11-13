package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.domain.AlertGate
import com.hmgcarapp.fuelwise.domain.AlertPolicy
import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.domain.ReplaceRules
import com.hmgcarapp.fuelwise.domain.VoiceIntent
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import com.hmgcarapp.fuelwise.navi.NaviReducer
import com.hmgcarapp.fuelwise.navi.NaviStatus
import com.hmgcarapp.fuelwise.navi.RoutePlans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix-F: N1~N4 */
class FixFTests {
    private fun quote() = Quote(
        Candidate("c", "강남 센트럴 주유소", "X", EnergyKind.FUEL, LatLon(0.0, 0.0)), 1700.0, 1700.0, null, 40.0, 68000, 2.5, 0, 68000, 0.0, 0.0, 0, 68000, 0, 0, true,
    )

    // N1
    @Test fun alertHeld_untilOriginReal_orTimeout() {
        val fire = AlertPolicy.Decision(true, false, "잔량 15%")
        // 실위치 전(originReady=false)에는 로드가 끝나도 보류하고 armed 를 소진하지 않는다
        assertEquals(AlertPolicy.Decision(false, true, null), AlertGate.hold(fire, loadDone = true, hasBest = true, currentArmed = true, originReady = false))
        assertEquals(AlertPolicy.Decision(false, true, null), AlertGate.hold(fire, loadDone = true, hasBest = false, currentArmed = true, originReady = false))
        // 실위치 수신 또는 20초 타임아웃 뒤(originReady=true)에는 기존 판정
        assertEquals(fire, AlertGate.hold(fire, loadDone = true, hasBest = true, currentArmed = true, originReady = true))
        assertEquals(20_000L, AlertGate.ORIGIN_TIMEOUT_MS)
    }

    @Test fun alertText_doesNotMentionUnverifiedLocation() {
        val q = quote()
        val t = AlertText.voice("잔량 15%", q, "주유", RoutePlans.ASK_START, locationUnverified = true)
        assertFalse(t, t.contains("기본 위치") || t.contains("현재 위치"))
        assertEquals(t, AlertText.voice("잔량 15%", q, "주유", RoutePlans.ASK_START))
    }

    // N2
    @Test fun replacePopup_hiddenWhileDriving() {
        assertEquals("x", ReplaceRules.visible("x", driving = false))
        assertNull(ReplaceRules.visible("x", driving = true))
        assertNull(ReplaceRules.visible<String>(null, driving = false))
    }

    // N3
    @Test fun settle_clearsFinishedButKeepsPending() {
        assertEquals(NaviStatus.Idle, NaviReducer.settle(NaviStatus.Success("경로 안내를 시작했어요", 3)))
        assertEquals(NaviStatus.Idle, NaviReducer.settle(NaviStatus.Failed("x", 3)))
        assertEquals(NaviStatus.Idle, NaviReducer.settle(NaviStatus.Idle))
        val p = NaviStatus.Pending("경로 요청 중", 4)
        assertEquals(p, NaviReducer.settle(p))
    }

    // N4
    @Test fun unknownQuestion_text_andPlainYesNotAccepted() {
        assertEquals("현재 경로를 바꿀까요? 바꾸려면 '바꿔줘'라고 말씀해 주세요", RoutePlans.askReplace("주유소"))
        assertTrue(VoiceIntent.isPlainYesOnly("네")); assertTrue(VoiceIntent.isPlainYesOnly("응"))
        assertFalse(VoiceIntent.isAccept("네", overwrite = true))
        assertFalse("명시 표현은 수락이므로 '단음절만' 아님", VoiceIntent.isPlainYesOnly("바꿔줘"))
        assertFalse("거절은 거절", VoiceIntent.isPlainYesOnly("아니요"))
        assertFalse("무관한 발화", VoiceIntent.isPlainYesOnly("오늘 날씨"))
        assertEquals("수락 안 됨", VoiceStatus.NOT_ACCEPTED)
    }

    // ---- P2/P3/P4 ----
    @Test fun keyedInterval_separatesKeys() {
        val k = com.hmgcarapp.fuelwise.domain.KeyedInterval(300)
        assertTrue(k.accept("fuel", 0)); assertFalse(k.accept("fuel", 100))
        assertTrue("다른 키는 막지 않는다", k.accept("alert", 100))
        assertTrue(k.accept("fuel", 300))
    }

    @Test fun failUnlessPending_doesNotOverwriteOtherRequest() {
        val p = NaviStatus.Pending("경로 요청 중", 7)
        assertEquals(p, NaviReducer.failUnlessPending(p, "x", 8))
        assertEquals(NaviStatus.Failed("x", 8), NaviReducer.failUnlessPending(NaviStatus.Idle, "x", 8))
    }

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun writeLatest_skipsUnchanged_andRewritesAfterClearAll() {
        val ss = com.hmgcarapp.fuelwise.app.StateStore(com.hmgcarapp.core.store.LocalJsonStore(tmp.newFolder("a")))
        val p = com.hmgcarapp.fuelwise.app.Persisted()
        assertEquals(ss.writeLatest { p }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        assertEquals("같은 내용은 다시 쓰지 않음", com.hmgcarapp.fuelwise.app.WriteResult.UNCHANGED, ss.writeLatest { p })
        assertEquals(ss.writeLatest { p.copy(page = com.hmgcarapp.fuelwise.app.Page.REPORT) }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        ss.clearAll { p }
        assertEquals("clearAll 뒤에는 기준이 초기화돼 다시 기록 가능", ss.writeLatest { p.copy(selectedId = "z") }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
    }

    @Test fun engine_expireOnly_dropsStaleValueWithoutPolling() {
        var now = 0L
        val eng = com.hmgcarapp.fuelwise.vehicle.EnergyEngine({ now }, ttlMs = 30_000)
        val id = eng.due().first { it.first == com.hmgcarapp.fuelwise.vehicle.Dom.FUEL }.second
        eng.fuelOk(id, 8)
        now = 30_000; eng.expireOnly(); assertEquals(8, eng.book.value.reading().fuelPercent)
        now = 30_001; eng.expireOnly()
        assertNull("포트 없이도 TTL 후 값이 사라진다", eng.book.value.reading().fuelPercent)
    }

    @Test fun engine_noResponseBoundary_4999vs5000ms() {
        var now = 0L
        val eng = com.hmgcarapp.fuelwise.vehicle.EnergyEngine({ now }, respTimeoutMs = 5_000)
        eng.due() // 요청 3건 전송(now=0)
        now = 4_999; eng.due()
        assertNull("4,999ms: 아직 무응답 판정 아님", eng.book.value.fuel.error)
        now = 5_000; eng.due()
        assertTrue("5,000ms: 무응답 실패", eng.book.value.fuel.error!!.startsWith("응답 없음"))
    }
}
