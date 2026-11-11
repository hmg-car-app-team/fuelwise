package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.app.UiState
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.DetourText
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EvalInput
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.RouteDestination
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.navi.DestinationProvider
import com.hmgcarapp.fuelwise.navi.RouteDestinationPoller
import com.hmgcarapp.fuelwise.navi.RouteMode
import com.hmgcarapp.fuelwise.navi.RouteStateProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDestinationTest {
    private val origin = LatLon(37.505, 127.0499)
    private val farDest = LatLon(36.80, 127.0499) // 정남쪽 약 78km
    private fun fuel(id: String, lat: Double, lon: Double, price: Int) =
        Candidate(id, id, "X", EnergyKind.FUEL, LatLon(lat, lon), mapOf(FuelGrade.GASOLINE to price))
    private fun eval(dest: LatLon?, list: List<Candidate>) =
        Recommender.evaluate(EvalInput(origin, dest, EnergyKind.FUEL, Settings(tankL = 40, kmPerL = 12.0), emptyList(), 40.0), list)

    // ---- 후보 범위 ----
    @Test fun candidatesFor_noDest_equalsNearby() {
        val list = listOf(fuel("in", 37.45, 127.0499, 1700), fuel("out", 37.10, 127.0499, 1700))
        assertEquals(Recommender.nearby(list, origin).map { it.id }, Recommender.candidatesFor(list, origin, null).map { it.id })
        assertEquals(listOf("in"), Recommender.candidatesFor(list, origin, null).map { it.id })
    }

    @Test fun candidatesFor_withDest_addsAheadBeyond20km_dropsBehindAndOffCorridorAndTooFar() {
        val list = listOf(
            fuel("near", 37.45, 127.0499, 1700),        // 반경 안(기존 동작 보존)
            fuel("ahead45", 37.10, 127.0499, 1700),     // 약 45km 앞, 경로상: 추가
            fuel("behind", 37.95, 127.0499, 1700),      // 약 50km 뒤: 우회 큼 -> 제외
            fuel("side", 37.10, 127.40, 1700),          // 앞쪽이지만 옆으로 멂: 우회 3km 초과 -> 제외
            fuel("beyond60", 36.90, 127.0499, 1700),    // 약 67km 앞: 60km 밖 -> 제외
        )
        assertEquals(listOf("near", "ahead45"), Recommender.candidatesFor(list, origin, farDest).map { it.id })
    }

    @Test fun candidatesFor_rangeLimitsExpandedOnly() {
        val list = listOf(
            fuel("near", 37.45, 127.0499, 1700),     // 약 6km: 반경 안
            fuel("ahead45", 37.10, 127.0499, 1700),  // 약 45km 앞
        )
        assertEquals(listOf("near"), Recommender.candidatesFor(list, origin, farDest, 30.0).map { it.id })
        assertEquals(listOf("near", "ahead45"), Recommender.candidatesFor(list, origin, farDest, null).map { it.id })
        assertEquals(listOf("near", "ahead45"), Recommender.candidatesFor(list, origin, farDest, 100.0).map { it.id })
        val oa = com.hmgcarapp.fuelwise.domain.CostEngine.haversineKm(origin, list[1].pos)
        assertEquals(listOf("near", "ahead45"), Recommender.candidatesFor(list, origin, farDest, oa).map { it.id }) // 경계 포함
        assertEquals(listOf("near"), Recommender.candidatesFor(list, origin, farDest, oa - 0.001).map { it.id })
        // 반경 안 후보는 주행가능거리가 짧아도 유지
        assertEquals(listOf("near"), Recommender.candidatesFor(list, origin, farDest, 1.0).map { it.id })
    }

    @Test fun candidatesFor_boundaries60kmAndDetour3km() {
        val cm = com.hmgcarapp.fuelwise.domain.CostEngine::haversineKm
        val d = Recommender.ROUTE_LOOKAHEAD_KM
        val edge = fuel("edge", 37.505 - d / 111.19, 127.0499, 1700)
        val dEdge = cm(origin, edge.pos)
        assertEquals(d, dEdge, 0.2)
        // 경계 직전(59.9km)은 포함, 경계 직후(60.5km)는 제외
        val justIn = fuel("justIn", 37.505 - (d - 0.3) / 111.19, 127.0499, 1700)
        assertEquals(listOf("justIn"), Recommender.candidatesFor(listOf(justIn), origin, farDest).map { it.id })
        val past = fuel("past", 37.505 - (d + 0.5) / 111.19, 127.0499, 1700)
        assertTrue(Recommender.candidatesFor(listOf(past), origin, farDest).isEmpty())
        // 우회 3.0km: 옆으로 벌려 우회가 한계 안/밖인 두 지점
        val inside = fuel("in", 37.10, 127.0499 + 0.002, 1700)
        val outside = fuel("out", 37.10, 127.0499 + 0.2, 1700)
        assertTrue(com.hmgcarapp.fuelwise.domain.DetourModel.detourKm(origin, farDest, inside.pos) <= Recommender.DETOUR_LIMIT_KM)
        assertTrue(com.hmgcarapp.fuelwise.domain.DetourModel.detourKm(origin, farDest, outside.pos) > Recommender.DETOUR_LIMIT_KM)
        assertEquals(listOf("in"), Recommender.candidatesFor(listOf(inside, outside), origin, farDest).map { it.id })
    }

    @Test fun candidatesFor_destWithin20km_keepsOnlyNearbyRadius() {
        val dest = LatLon(37.45, 127.0499) // 약 6km
        val list = listOf(fuel("in", 37.40, 127.0499, 1700), fuel("out", 36.95, 127.0499, 1700))
        assertEquals(listOf("in"), Recommender.candidatesFor(list, origin, dest).map { it.id })
    }

    // ---- 방향성: 목적지 주입 시 순위가 갈린다 ----
    @Test fun direction_aheadVsBehind_rankFlipsWhenDestinationInjected() {
        val ahead = fuel("ahead", 37.45, 127.0499, 1700)   // 목적지 쪽 6km
        val behind = fuel("behind", 37.56, 127.0499, 1650) // 반대쪽 6km, 50원 저렴
        val list = listOf(ahead, behind)
        // 목적지 없음: 두 후보의 왕복 우회가 같아 가격이 싼 뒤쪽 후보가 이긴다(방향 무시)
        val none = eval(null, list)
        assertEquals("behind", Recommender.best(none)!!.candidate.id)
        assertEquals(none.first { it.candidate.id == "ahead" }.detourKm, none.first { it.candidate.id == "behind" }.detourKm, 0.05)
        // 목적지 주입: 뒤쪽 후보는 우회가 커져 앞쪽 후보가 이긴다
        val with = eval(farDest, list)
        val qa = with.first { it.candidate.id == "ahead" }; val qb = with.first { it.candidate.id == "behind" }
        assertTrue("ahead=${qa.detourKm} behind=${qb.detourKm}", qa.detourKm < 0.1 && qb.detourKm > 10.0)
        assertEquals("ahead", Recommender.best(with)!!.candidate.id)
    }

    // ---- 목적지 질의 폴백 ----
    private class Clock(var t: Long = 0L) : () -> Long { override fun invoke() = t }
    private fun poller(mode: suspend () -> RouteMode, dest: suspend () -> RouteDestination?, clock: Clock = Clock(), timeoutMs: Long = 2500L) =
        RouteDestinationPoller(RouteStateProvider { mode() }, DestinationProvider { dest() }, clock, 30_000L, timeoutMs)
    private val dst = RouteDestination(LatLon(37.3948, 127.1112), "도착지", 12000)

    @Test fun poll_activeRoute_returnsDestination() = runBlocking {
        assertEquals(dst, poller({ RouteMode.ACTIVE }, { dst }).poll())
    }

    @Test fun poll_noRoute_unknown_orFailure_fallBackToNull() = runBlocking {
        assertNull(poller({ RouteMode.NONE }, { dst }).poll())
        assertNull(poller({ RouteMode.UNKNOWN }, { dst }).poll())
        assertNull(poller({ RouteMode.ACTIVE }, { null }).poll())                       // 미지원/무응답 보고
        assertNull(poller({ RouteMode.ACTIVE }, { throw IllegalStateException("x") }).poll())
        assertNull(poller({ throw IllegalStateException("x") }, { dst }).poll())
        assertNull(poller({ RouteMode.ACTIVE }, { delay(10_000); dst }, timeoutMs = 50).poll()) // 시간 초과
        assertNull(poller({ RouteMode.ACTIVE }, { RouteDestination(LatLon(0.0, 0.0)) }).poll()) // 잘못된 좌표
        assertNull(poller({ RouteMode.ACTIVE }, { RouteDestination(LatLon(200.0, 10.0)) }).poll())
    }

    @Test fun poll_isThrottled_to30s() = runBlocking {
        val c = Clock(1000)
        val p = poller({ RouteMode.ACTIVE }, { dst }, c)
        assertTrue(p.due()); p.poll()
        assertFalse(p.due()); c.t += 29_999; assertFalse(p.due()); c.t += 1; assertTrue(p.due())
    }

    @Test fun routeEnds_poll_returnsNull_thenDestinationChange_isPicked() = runBlocking {
        var mode = RouteMode.ACTIVE; var d: RouteDestination? = dst
        val p = poller({ mode }, { d })
        assertEquals(dst, p.poll())
        val other = RouteDestination(LatLon(37.0, 127.3), "다른 곳")
        d = other; assertEquals(other, p.poll())
        mode = RouteMode.NONE; assertNull(p.poll()) // 안내 종료
    }

    // ---- UiState 평가 목적지 / 표기 ----
    @Test fun evalDest_usesRouteDest_andFallsBackToNull() {
        assertNull(UiState().evalDest)                                       // 현행(목적지 없음) 동작
        assertEquals(dst.pos, UiState(routeDest = dst).evalDest)
        assertNull(UiState(routeDest = null).evalDest)
    }

    @Test fun evalDest_demoDestinationTakesPrecedenceOverRouteDest() {
        val demo = com.hmgcarapp.fuelwise.domain.Presets.destinations[0]
        val st = UiState(p = com.hmgcarapp.fuelwise.app.Persisted(settings = Settings(destinationIndex = 0)), debuggable = true, routeDest = RouteDestination(LatLon(37.0, 127.3), "경로 목적지"))
        assertEquals(demo.pos, st.evalDest)
        assertNotEquals(LatLon(37.0, 127.3), st.evalDest)
        // 비 debuggable 에서는 시연 목적지를 무시하고 경로 목적지를 쓴다
        assertEquals(LatLon(37.0, 127.3), st.copy(debuggable = false).evalDest)
    }

    @Test fun poll_destinationQueryThrows_fallsBackToNullAndRepollsLater() = runBlocking {
        val c = Clock(0); var boom = true
        val p = poller({ RouteMode.ACTIVE }, { if (boom) throw IllegalStateException("x") else dst }, c)
        assertNull(p.poll()); assertFalse(p.due())
        boom = false; c.t += 30_000; assertTrue(p.due()); assertEquals(dst, p.poll())
    }

    @Test fun detourText_distinguishesRouteEstimateFromRoundTrip() {
        assertNotEquals(DetourText.label(true), DetourText.label(false))
        assertEquals("경로 추정 우회(직선 근사)", DetourText.label(true))
        assertEquals("왕복 추정(목적지 없음)", DetourText.label(false))
    }
}
