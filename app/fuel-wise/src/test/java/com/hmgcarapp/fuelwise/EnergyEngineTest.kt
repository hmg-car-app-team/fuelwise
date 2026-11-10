package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.vehicle.Dom
import com.hmgcarapp.fuelwise.vehicle.EnergyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K2: 신호 유지 TTL·무응답 실패·null=실패·게이트 리셋(시간 주입). */
class EnergyEngineTest {
    private var now = 0L
    private fun engine() = EnergyEngine({ now })
    private fun EnergyEngine.id(d: Dom, due: List<Pair<Dom, Int>>) = due.first { it.first == d }.second

    @Test fun firstDue_requestsAllThree() {
        val e = engine()
        assertEquals(setOf(Dom.FUEL, Dom.EV, Dom.RANGE), e.due().map { it.first }.toSet())
        assertTrue("응답 전에는 중복 요청 없음", e.due().isEmpty())
    }

    @Test fun lastGoodValue_isKeptFor30s_afterFailure_thenDropped() {
        val e = engine()
        val id1 = e.id(Dom.FUEL, e.due()); e.fuelOk(id1, 50)
        now = 3_000; val id2 = e.id(Dom.FUEL, e.due())
        e.fail(Dom.FUEL, id2, "boom")
        var r = e.book.value.reading()
        assertEquals(50, r.fuelPercent); assertEquals("boom", r.fuelError) // 값 유지 + 오류만 기록
        now = 30_000; e.due(); assertEquals(50, e.book.value.reading().fuelPercent) // 정확히 TTL 경계: 유지
        now = 30_001; e.due(); r = e.book.value.reading()
        assertNull(r.fuelPercent); assertNotNull(r.fuelError)
    }

    @Test fun noCallback_for5s_countsAsFailure_andPausesAfterFiveTimes() {
        val e = engine()
        e.due()                          // t=0 요청 1
        now = 4_999; assertTrue(e.due().none { it.first == Dom.FUEL })
        now = 5_000                      // 무응답 5초 -> 실패 1, 같은 틱에 재요청
        assertTrue(e.due().any { it.first == Dom.FUEL })
        assertTrue(e.book.value.reading().fuelError!!.contains("응답 없음"))
        repeat(4) { now += 5_000; e.due() } // 실패 2~5
        assertTrue(e.isPaused(Dom.FUEL))
        now += 5_000; assertTrue("일시정지 중에는 요청하지 않음", e.due().none { it.first == Dom.FUEL })
    }

    @Test fun retryNow_resetsPausedGates_immediately() {
        val e = engine()
        repeat(5) { // 콜백 오류 5연속 -> 정지
            val id = e.id(Dom.EV, e.due()); e.fail(Dom.EV, id, "err"); now += 3_000
        }
        assertTrue(e.isPaused(Dom.EV))
        assertTrue(e.due().none { it.first == Dom.EV })
        e.retryNow()                      // 앱 시작/재개·권한 부여
        assertFalse(e.isPaused(Dom.EV))
        assertTrue(e.due().any { it.first == Dom.EV })
    }

    @Test fun nullCallback_isFailure_notSuccess() {
        val e = engine()
        val id = e.id(Dom.FUEL, e.due()); e.fuelOk(id, null)
        val r = e.book.value.reading()
        assertNull(r.fuelPercent); assertEquals("값 없음(null)", r.fuelError)
        // 실패로 세어지므로 5번 누적되면 정지(성공이면 카운터가 초기화되어 정지하지 않는다)
        repeat(4) { now += 3_000; val i = e.id(Dom.FUEL, e.due()); e.fuelOk(i, null) }
        assertTrue(e.isPaused(Dom.FUEL))
    }

    @Test fun rangeZero_isFailure_butKeepsGoodRangeWithinTtl() {
        val e = engine()
        val id1 = e.id(Dom.RANGE, e.due()); e.rangeOk(id1, 123_000f)
        assertEquals(123f, e.book.value.reading().rangeKm)
        now = 3_000; val id2 = e.id(Dom.RANGE, e.due()); e.rangeOk(id2, 0f)
        val r = e.book.value.reading()
        assertEquals(123f, r.rangeKm); assertEquals("0 (값 없음)", r.rangeError)
    }

    @Test fun staleFailure_doesNotClearNewPendingRequest() {
        val e = engine()
        val id1 = e.id(Dom.FUEL, e.due())
        now = 5_000; val id2 = e.id(Dom.FUEL, e.due()) // id1 은 무응답 처리, id2 요청
        e.fail(Dom.FUEL, id1, "늦은 실패")
        assertTrue("새 요청은 여전히 대기 중", e.book.value.fuel.pendingSince != null)
        assertTrue(e.due().none { it.first == Dom.FUEL })
        e.fuelOk(id2, 40); assertEquals(40, e.book.value.reading().fuelPercent)
    }

    @Test fun sdkUnavailable_recordsErrorsOnly() {
        val e = engine(); e.unavailable("SDK 불가")
        val r = e.book.value.reading()
        assertNull(r.fuelPercent); assertEquals("SDK 불가", r.evError)
    }

    // ---- C1: 주행가능거리 0 은 게이트 실패가 아니다 ----
    @Test fun rangeZeroAtStart_neverPauses_andNewValueShowsWithinOnePoll() {
        val e = engine()
        repeat(30) { // 90초 동안 매 3초 주기마다 응답은 오지만 값은 0
            val id = e.id(Dom.RANGE, e.due()) // 요청이 계속 나간다 = 일시정지 없음(id() 는 요청이 없으면 예외)
            e.rangeOk(id, 0f); now += 3_000
        }
        assertFalse(e.isPaused(Dom.RANGE))
        var r = e.book.value.reading()
        assertNull(r.rangeKm); assertEquals("0 (값 없음)", r.rangeError)
        assertNull("요청 대기는 해제됨", e.book.value.range.pendingSince)
        // VC 값이 40km 로 바뀜 -> 다음 주기(3초 이내)에 반영
        val id = e.id(Dom.RANGE, e.due()); e.rangeOk(id, 40_000f)
        r = e.book.value.reading()
        assertEquals(40f, r.rangeKm); assertNull(r.rangeError)
    }

    @Test fun rangeNullResponse_stillCountsAsFailure_butZeroDoesNot() {
        val e = engine()
        repeat(5) { val id = e.id(Dom.RANGE, e.due()); e.rangeOk(id, null); now += 3_000 }
        assertTrue("null 응답은 실패로 센다", e.isPaused(Dom.RANGE))
    }

    @Test fun rangeOk_nanInfinityNegative_areNoValue_notFailures() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -5f, -0.0f, 0f).forEach { raw ->
            val e = engine()
            repeat(10) { val id = e.id(Dom.RANGE, e.due()); e.rangeOk(id, raw); now += 3_000 }
            assertFalse("$raw", e.isPaused(Dom.RANGE)); assertNull("$raw", e.book.value.reading().rangeKm)
        }
    }

    @Test fun zeroResponse_afterGoodValue_keepsValueUntilTtl() {
        val e = engine()
        e.rangeOk(e.id(Dom.RANGE, e.due()), 100_000f)
        now = 3_000; e.rangeOk(e.id(Dom.RANGE, e.due()), 0f)
        assertEquals(100f, e.book.value.reading().rangeKm)
        now = 30_001; e.due(); assertNull("TTL 경과 후 값 폐기", e.book.value.reading().rangeKm)
    }

    // ---- C16(b) ----
    @Test fun pausedGate_reRequestsAt60000ms_andRepausesOnFailure() {
        val e = engine()
        var pausedAt = 0L
        repeat(5) { pausedAt = now; e.fail(Dom.EV, e.id(Dom.EV, e.due()), "err"); now += 100 }
        assertTrue(e.isPaused(Dom.EV))
        now = pausedAt + 59_999; assertTrue(e.due().none { it.first == Dom.EV })
        now = pausedAt + 60_000; val again = e.due().filter { it.first == Dom.EV }
        assertEquals("정확히 60,000ms 에 1회 재요청", 1, again.size)
        e.fail(Dom.EV, again.single().second, "err")
        assertTrue("재탐색이 실패하면 다시 정지", e.isPaused(Dom.EV))
        now += 1_000; assertTrue(e.due().none { it.first == Dom.EV })
    }

    @Test fun lateSuccess_ofTimedOutRequest_isAcceptedWithoutClearingNewPending() {
        val e = engine()
        val id1 = e.id(Dom.FUEL, e.due())
        now = 5_000; val id2 = e.id(Dom.FUEL, e.due()) // id1 은 5초 무응답 -> id2 요청
        e.fuelOk(id1, 55) // 타임아웃된 요청의 늦은 성공
        assertEquals(55, e.book.value.reading().fuelPercent)
        assertNull("늦은 성공이 새 요청의 오류 표시를 지움", e.book.value.reading().fuelError)
        assertNotNull("새 요청은 계속 대기 중", e.book.value.fuel.pendingSince)
        e.fuelOk(id2, 56); assertEquals(56, e.book.value.reading().fuelPercent); assertNull(e.book.value.fuel.pendingSince)
    }

    @Test fun ttlExpires_evenWhileGateIsPaused() {
        val e = engine()
        e.fuelOk(e.id(Dom.FUEL, e.due()), 50)
        repeat(5) { now += 100; e.fail(Dom.FUEL, e.id(Dom.FUEL, e.due()), "err") }
        assertTrue(e.isPaused(Dom.FUEL))
        now = 30_000; e.due(); assertEquals(50, e.book.value.reading().fuelPercent) // 마지막 정상 시각(0) 기준 경계
        now = 30_001; e.due(); assertNull("정지 중에도 TTL 은 만료", e.book.value.reading().fuelPercent)
    }

    // ---- C2: 시계가 뒤로 가도 값이 영원히 남지 않는다 ----
    @Test fun clockGoingBackwards_expiresValue_insteadOfKeepingItForever() {
        val e = engine()
        now = 100_000; e.fuelOk(e.id(Dom.FUEL, e.due()), 50)
        now = 10_000; e.due()
        assertNull(e.book.value.reading().fuelPercent)
    }

    @Test fun pollGate_pauseFarInTheFuture_isIgnoredWhenClockJumpsBack() {
        val g = com.hmgcarapp.fuelwise.vehicle.PollGate(1, 60_000)
        g.onFailure(1_000_000); assertFalse(g.shouldPoll(1_000_001))
        assertTrue("시계가 크게 뒤로 가면 정지에 갇히지 않는다", g.shouldPoll(10_000))
    }

    // ---- M7 settled 신호 ----
    @Test fun settled_afterValueOrAnyFailure() {
        val e = engine()
        val ids = e.due().toMap()
        e.evOk(ids.getValue(Dom.EV), 40)
        var r = e.book.value.reading(); assertTrue(r.evSettled); assertFalse(r.fuelSettled)
        e.fuelOk(ids.getValue(Dom.FUEL), null) // null 응답 = 실패 확정
        r = e.book.value.reading(); assertTrue(r.fuelSettled)
    }
}
