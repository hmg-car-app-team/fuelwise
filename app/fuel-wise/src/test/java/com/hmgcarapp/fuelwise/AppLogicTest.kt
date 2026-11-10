package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.app.ReportEntry
import com.hmgcarapp.fuelwise.data.CandidateCache
import com.hmgcarapp.fuelwise.data.StationRepository
import com.hmgcarapp.fuelwise.domain.AlertText
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EnergyMode
import com.hmgcarapp.fuelwise.domain.EnergyResolver
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.MoveGate
import com.hmgcarapp.fuelwise.domain.PermissionFilter
import com.hmgcarapp.fuelwise.domain.Quote
import com.hmgcarapp.fuelwise.domain.ReportLogic
import com.hmgcarapp.fuelwise.domain.SimField
import com.hmgcarapp.fuelwise.domain.SimInput
import com.hmgcarapp.fuelwise.domain.VoiceStatus
import com.hmgcarapp.fuelwise.navi.RoutePlans
import com.hmgcarapp.fuelwise.vehicle.PollGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AppLogicTest {
    // ---- EnergyResolver ----
    private fun res(mode: EnergyMode = EnergyMode.AUTO, simFuel: Int? = null, simSoc: Int? = null, simRange: Float? = null, fuel: Int? = null, soc: Int? = null, range: Float? = null) =
        EnergyResolver.resolve(mode, 30, simFuel, simSoc, simRange, fuel, soc, range)

    @Test fun auto_picksKindFromSignals() {
        assertEquals(EnergyKind.FUEL, res(fuel = 50).kind)
        assertEquals(EnergyKind.EV, res(soc = 50).kind)
        assertEquals(EnergyKind.FUEL, res().kind) // 신호 없음 -> 내연기관 가정
        assertEquals(EnergyKind.EV, res(simSoc = 40, fuel = 50).kind) // 시연 값이 실신호보다 우선
        assertEquals(EnergyKind.FUEL, res(mode = EnergyMode.FUEL, soc = 50).kind)
        assertEquals(EnergyKind.EV, res(mode = EnergyMode.EV, fuel = 50).kind)
    }

    @Test fun level_andSource_fallback() {
        val none = res()
        assertEquals(30, none.level); assertEquals(null, none.real); assertEquals("설정값(신호 없음)", none.source)
        val sdk = res(fuel = 12)
        assertEquals(12, sdk.level); assertEquals("차량 신호", sdk.source)
        assertEquals(5, res(simFuel = 5, fuel = 12).level)
    }

    @Test fun simRangeZero_isNoValue() {
        assertEquals(80f, res(simRange = 0f, range = 80f).rangeKm)
        assertEquals(null, res(simRange = 0f).rangeKm)
        assertEquals(150f, res(simRange = 150f, range = 80f).rangeKm)
        assertEquals(80f, res(simRange = Float.NaN, range = 80f).rangeKm)
    }

    // ---- MoveGate (D4b) ----
    @Test fun moveGate_acceptsOnlyAfter100m() {
        val g = MoveGate(0.1)
        val a = LatLon(37.5, 127.0)
        assertTrue(g.accept(a)) // 첫 위치
        assertFalse(g.accept(LatLon(37.5005, 127.0))) // 약 56m
        assertTrue(g.accept(LatLon(37.5015, 127.0)))  // 기준(a)에서 약 167m
        assertFalse(g.accept(LatLon(37.5016, 127.0))) // 새 기준에서 약 11m
    }

    // ---- CandidateCache (F4a, D4b 2km) ----
    @Test fun candidateCache_keepsOldList_andOrigin_onFailure() = runBlocking {
        var fail = false
        val repo = object : StationRepository {
            override suspend fun candidates(origin: LatLon): List<Candidate> {
                if (fail) throw IllegalStateException("boom")
                return listOf(Candidate("a", "A", "X", EnergyKind.FUEL, origin))
            }
        }
        val cache = CandidateCache(repo)
        val o1 = LatLon(37.5, 127.0)
        assertTrue(cache.needsReload(o1))
        assertTrue(cache.refresh(o1))
        assertFalse(cache.needsReload(LatLon(37.505, 127.0))) // 약 0.55km < 2km
        val far = LatLon(37.53, 127.0) // 약 3.3km
        assertTrue(cache.needsReload(far))
        fail = true
        assertFalse(cache.refresh(far))
        assertEquals(listOf("a"), cache.candidates.map { it.id }) // 기존 후보 유지
        assertEquals(o1, cache.lastLoadOrigin)                    // 기준 위치 갱신 안 함
        assertTrue(cache.needsReload(far))                        // 다음 위치 갱신에서 재시도
        fail = false
        assertTrue(cache.refresh(far)); assertEquals(far, cache.lastLoadOrigin)
    }

    // ---- AlertText (E4, B2) ----
    private fun quote(name: String, base: Boolean, net: Long, km: Double = 2.5) = Quote(
        Candidate("c", name, "X", EnergyKind.FUEL, LatLon(0.0, 0.0)), 1700.0, 1700.0, null, 40.0, 68000, km, 0, 68000, 0.0, 0.0, 0, 68000, net, 0, base,
    )

    @Test fun alertText_baseline_hasReasonWithoutSaving() {
        val t = AlertText.voice("잔량 15%", quote("강남 센트럴 주유소", true, 0), "주유", RoutePlans.ASK_START)
        assertTrue(t, t.contains("강남 센트럴 주유소가"))
        assertTrue(t, t.contains("우회가 가장 적어") && t.contains("2.5km"))
        assertFalse(t, t.contains("절약"))
        assertTrue(t, t.endsWith("안내를 시작할까요?"))
    }

    @Test fun alertText_saving_josaAndWaypointAsk() {
        val t = AlertText.voice("잔량 15%", quote("서초 충전탑", false, 1350), "충전", RoutePlans.ASK_VIA)
        assertTrue(t, t.contains("서초 충전탑이 대략 2.5km 돌아가도 실질 1,350원 절약"))
        assertTrue(t, t.endsWith("경유지로 추가할까요?"))
        assertTrue(AlertText.voice("잔량 15%", quote("A", false, -5), "주유", RoutePlans.ASK_VIA).contains("실질 비용이 가장 낮아요"))
        assertEquals("잔량 15%. 근처 주유 후보를 찾지 못했어요.", AlertText.voice("잔량 15%", null, "주유", RoutePlans.ASK_START))
    }

    @Test fun bannerLine_isAtMost40Chars_andIdleLine() {
        val long = quote("아주아주아주아주아주아주아주아주아주아주 긴 이름의 주유소입니다", false, 100)
        val line = AlertText.line("잔량 15%, 주행가능거리 40km", long)
        assertTrue(line, line.length <= AlertText.BANNER_MAX && line.endsWith("약 100원 절약") && line.contains("…")) // 이름은 줄이고 절약액은 남긴다
        assertEquals("잔량 15% · 근처 후보를 찾지 못했어요", AlertText.line("잔량 15%", null))
        assertEquals("잔량 부족 · 근처 후보를 찾지 못했어요", AlertText.line("잔량 부족", null))
        assertEquals("알림 대기 중 · 주행가능거리 123km", AlertText.idleLine(123.4f))
        assertEquals("알림 대기 중", AlertText.idleLine(null))
        listOf(VoiceStatus.SPEAKING, VoiceStatus.LISTENING, VoiceStatus.ACCEPTED, VoiceStatus.DECLINED, VoiceStatus.FAILED, VoiceStatus.NO_REPLY, VoiceStatus.NOT_ACCEPTED, VoiceStatus.VOICE_ONLY)
            .forEach { assertTrue(it, it.length <= 6) } // 음성 상태는 짧은 고정 표기(C11: '발화 중'/'듣는 중'; 원문·사유 없음)
    }

    // ---- ReportLogic (E5, 월·연도 경계) ----
    private val utc = TimeZone.getTimeZone("UTC")
    private fun entry(ts: Long, name: String = "A", saved: Long = 100, sample: Boolean = false) = ReportEntry(ts, name, EnergyKind.FUEL, 10.0, 1000, saved, sample)

    @Test fun monthKey_changesAtMonthAndYearBoundary() {
        val newYear = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        assertEquals("2025-12", ReportLogic.monthKey(newYear - 1000, utc))
        assertEquals("2026-01", ReportLogic.monthKey(newYear, utc))
        assertEquals("2026-02", ReportLogic.monthKey(newYear + 31L * 86_400_000L, utc))
    }

    @Test fun monthTotals_excludeSamples_andOtherMonths() {
        val t = 1_767_225_600_000L
        val items = listOf(entry(t + 1000, saved = 300), entry(t + 2000, name = "B", saved = 200), entry(t + 3000, name = "S", saved = 9999, sample = true), entry(t - 86_400_000L, name = "Dec", saved = 50))
        assertEquals(500L, ReportLogic.monthSaved(items, "2026-01", utc))
        assertEquals(2, ReportLogic.monthCount(items, "2026-01", utc))
        assertEquals(2000L, ReportLogic.monthSpent(items, "2026-01", utc))
        assertEquals(550L, ReportLogic.totalSaved(items))
        assertEquals(50L, ReportLogic.monthSaved(items, "2025-12", utc)) // 월이 바뀌면 지난달 값이 이번 달에서 빠진다
    }

    @Test fun duplicateRecording_within60s() {
        val t = 1_767_225_600_000L
        val items = listOf(entry(t, name = "A"))
        assertTrue(ReportLogic.isDuplicate(items, "A", t + 59_000))
        assertFalse(ReportLogic.isDuplicate(items, "A", t + 60_000))
        assertFalse(ReportLogic.isDuplicate(items, "B", t + 1000))
        assertFalse(ReportLogic.isDuplicate(listOf(entry(t, name = "A", sample = true)), "A", t + 1000)) // 샘플은 중복 판단 제외
    }

    // ---- SimInput (A1) ----
    @Test fun simInput_validation() {
        assertEquals(SimField.Set(0), SimInput.percent(0)); assertEquals(SimField.Set(100), SimInput.percent(100))
        assertEquals(SimField.Clear, SimInput.percent(-1))
        assertEquals(SimField.Ignore, SimInput.percent(101)); assertEquals(SimField.Ignore, SimInput.percent(-50)); assertEquals(SimField.Ignore, SimInput.percent(Int.MIN_VALUE))
        assertEquals(SimField.Set(250f), SimInput.range(250f)); assertEquals(SimField.Set(0f), SimInput.range(0f))
        assertEquals(SimField.Clear, SimInput.range(-1f))
        assertEquals(SimField.Ignore, SimInput.range(Float.NaN)); assertEquals(SimField.Ignore, SimInput.range(1e9f)); assertEquals(SimField.Ignore, SimInput.range(-7f))
        assertEquals(EnergyMode.EV, SimInput.mode("ev")); assertEquals(null, SimInput.mode("bogus")); assertEquals(null, SimInput.mode(null))
        assertEquals(SimField.Set(true), SimInput.drive("driving"))
        assertEquals(SimField.Clear, SimInput.drive("PARKED")) // PARKED 는 override 가 아니라 실신호 복귀
        assertEquals(SimField.Ignore, SimInput.drive("x")); assertEquals(SimField.Ignore, SimInput.drive(null))
    }

    // ---- PollGate (D2) ----
    @Test fun pollGate_pausesAfter5Failures_retriesEvery60s() {
        val g = PollGate(5, 60_000)
        var now = 1_000L
        repeat(4) { assertTrue(g.shouldPoll(now)); g.onFailure(now); now += 3000 }
        assertTrue(g.shouldPoll(now)); g.onFailure(now) // 5번째 실패 -> 중단
        assertFalse(g.shouldPoll(now + 3000)); assertFalse(g.shouldPoll(now + 59_999))
        assertTrue(g.shouldPoll(now + 60_000))          // 60초 뒤 재탐색 1회
        g.onFailure(now + 60_000)
        assertFalse(g.shouldPoll(now + 63_000))         // 또 실패하면 다시 중단
        g.onSuccess()
        assertTrue(g.shouldPoll(now + 63_000))          // 성공하면 정상 폴링 복귀
        repeat(4) { g.onFailure(now) }; assertTrue(g.shouldPoll(now)) // 카운터 초기화됨
    }

    // ---- PermissionFilter (G5) ----
    @Test fun permissionFilter_dropsUndeclared() {
        val declared = listOf("android.permission.ACCESS_FINE_LOCATION", "android.car.permission.CAR_SPEED")
        val missing = listOf("android.car.permission.CAR_SPEED", "android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_FINE_LOCATION")
        assertEquals(listOf("android.car.permission.CAR_SPEED", "android.permission.ACCESS_FINE_LOCATION"), PermissionFilter.requestable(missing, declared))
        assertTrue(PermissionFilter.requestable(missing, emptyList()).isEmpty())
    }

    @Test fun pollGate_reset_clearsPause() {
        val g = PollGate(2, 60_000)
        g.onFailure(0); g.onFailure(0)
        assertFalse(g.shouldPoll(1_000))
        g.reset(); assertTrue(g.shouldPoll(1_000))
        g.onFailure(1_000); assertTrue("카운터도 초기화", g.shouldPoll(1_000))
    }

    // ---- K1/K12: 모드 x 신호 조합 (기대값을 표로 직접 기술) ----
    private data class Case(val mode: EnergyMode, val fuel: Int?, val soc: Int?, val last: EnergyKind?, val kind: EnergyKind, val level: Int, val source: String)

    @Test fun modeBySignalMatrix() {
        val sdk = "차량 신호"; val none = "설정값(신호 없음)"
        val F = EnergyKind.FUEL; val E = EnergyKind.EV; val A = EnergyMode.AUTO
        listOf(
            // AUTO
            Case(A, null, null, null, F, 30, none), Case(A, 12, null, null, F, 12, sdk), Case(A, null, 40, null, E, 40, sdk),
            Case(A, 12, 40, null, F, 12, sdk),          // 둘 다: 이전 기록이 없으면 내연기관
            Case(A, 12, 40, E, E, 40, sdk),             // 둘 다: 마지막으로 확인된 도메인 유지
            Case(A, null, null, E, E, 30, none),        // 신호가 모두 사라져도 마지막 도메인 유지(뒤집지 않음), 잔량만 설정값
            Case(A, 0, null, null, F, 0, sdk),          // fuel=0 은 '값 있음'(연료 0%)
            Case(A, 0, 55, null, F, 0, sdk),            // fuel=0 + soc: 신호 둘 다 있음 -> 기본은 연료(이전 기록 없을 때)
            // FUEL 고정
            Case(EnergyMode.FUEL, null, null, null, F, 30, none), Case(EnergyMode.FUEL, 12, null, null, F, 12, sdk),
            Case(EnergyMode.FUEL, null, 40, null, F, 30, none), Case(EnergyMode.FUEL, 12, 40, null, F, 12, sdk),
            // EV 고정
            Case(EnergyMode.EV, null, null, null, E, 30, none), Case(EnergyMode.EV, null, 40, null, E, 40, sdk),
            Case(EnergyMode.EV, 12, null, null, E, 30, none), Case(EnergyMode.EV, 12, 40, null, E, 40, sdk),
        ).forEach { c ->
            val r = EnergyResolver.resolve(c.mode, 30, null, null, null, c.fuel, c.soc, null, c.last)
            assertEquals("$c", c.kind, r.kind); assertEquals("$c", c.level, r.level); assertEquals("$c", c.source, r.source)
        }
    }

    @Test fun sticky_isUpdatedOnlyBySdkConfirmationInAuto() {
        assertEquals(EnergyKind.EV, EnergyResolver.resolve(EnergyMode.AUTO, 30, null, null, null, null, 40, null).sticky)
        assertEquals(EnergyKind.FUEL, EnergyResolver.resolve(EnergyMode.AUTO, 30, null, null, null, 10, null, null, EnergyKind.EV).sticky) // 반대 도메인만 보이면 전환
        assertEquals(EnergyKind.EV, EnergyResolver.resolve(EnergyMode.AUTO, 30, null, null, null, null, null, null, EnergyKind.EV).sticky) // 신호 소실: 유지
        assertEquals(null, EnergyResolver.resolve(EnergyMode.AUTO, 30, 5, null, null, null, null, null, null).sticky) // 시연 값은 기록하지 않음
        assertEquals(null, EnergyResolver.resolve(EnergyMode.FUEL, 30, null, null, null, 10, null, null, null).sticky)
    }

    // ---- M7: sticky 는 두 도메인이 한 사이클(값 또는 실패 확정)을 마친 뒤에만 확정 ----
    private fun auto(fuel: Int?, soc: Int?, last: EnergyKind?, fuelSettled: Boolean, evSettled: Boolean) =
        EnergyResolver.resolve(EnergyMode.AUTO, 30, null, null, null, fuel, soc, null, last, fuelSettled, evSettled)

    @Test fun sticky_notCommitted_whileOtherDomainStillPending() {
        // EV 가 먼저 응답, FUEL 은 아직 첫 응답/실패 전: 잠정 EV, sticky 는 확정하지 않는다
        val r = auto(null, 40, null, fuelSettled = false, evSettled = true)
        assertEquals(EnergyKind.EV, r.kind); assertEquals(null, r.sticky)
        // 반대 순서(FUEL 먼저)도 마찬가지
        val f = auto(12, null, null, fuelSettled = true, evSettled = false)
        assertEquals(EnergyKind.FUEL, f.kind); assertEquals(null, f.sticky)
    }

    @Test fun sticky_arrivalOrderDoesNotPinTheFirstResponder() {
        // EV 먼저 -> FUEL 이 뒤늦게 도착: 둘 다 있으면 (확정된 이전 기록 없이) 연료. 첫 응답 도메인(EV)으로 굳지 않는다
        val first = auto(null, 40, null, false, true)
        val second = auto(12, 40, first.sticky, true, true)
        assertEquals(EnergyKind.FUEL, second.kind); assertEquals(EnergyKind.FUEL, second.sticky)
        // FUEL 먼저 -> EV 뒤늦게: 결과 동일(순서 무관)
        val f1 = auto(12, null, null, true, false)
        val f2 = auto(12, 40, f1.sticky, true, true)
        assertEquals(second.kind, f2.kind); assertEquals(second.sticky, f2.sticky)
    }

    @Test fun singleEnergyVehicle_commitsOnceOtherDomainFailedOrTimedOut() {
        // 전기차: FUEL 이 오류/타임아웃으로 '확정'(settled)되면 EV 로 확정
        val ev = auto(null, 40, null, fuelSettled = true, evSettled = true)
        assertEquals(EnergyKind.EV, ev.kind); assertEquals(EnergyKind.EV, ev.sticky)
        // 내연기관 차량도 대칭
        val fuel = auto(12, null, null, true, true)
        assertEquals(EnergyKind.FUEL, fuel.kind); assertEquals(EnergyKind.FUEL, fuel.sticky)
        // 확정 뒤 일시 소실: 유지
        assertEquals(EnergyKind.EV, auto(null, null, ev.sticky, true, true).kind)
    }

    @Test fun energyReading_settled_isValueOrError() {
        val r = com.hmgcarapp.fuelwise.vehicle.EnergyReading()
        assertFalse(r.fuelSettled); assertFalse(r.evSettled)
        assertTrue(r.copy(fuelPercent = 0).fuelSettled); assertTrue(r.copy(evError = "x").evSettled)
    }

    @Test fun effectiveMode_andNote() {
        assertEquals(EnergyMode.FUEL, EnergyResolver.effectiveMode(EnergyMode.FUEL, EnergyMode.AUTO)) // 시연 모드가 우선
        assertEquals(EnergyMode.EV, EnergyResolver.effectiveMode(null, EnergyMode.EV))                 // 없으면 저장된 설정
        assertEquals(null, EnergyResolver.modeNote(EnergyMode.AUTO, false))
        assertEquals("(설정: 내연기관)", EnergyResolver.modeNote(EnergyMode.FUEL, false))
        assertEquals("(설정: 전기차)", EnergyResolver.modeNote(EnergyMode.EV, false))
        assertEquals("(시연: 전기차)", EnergyResolver.modeNote(EnergyMode.EV, true))
    }

    @Test fun simInput_numbersFromAnyExtraType_andModeField() {
        assertEquals(SimField.Set(12), SimInput.percentAny(12)); assertEquals(SimField.Set(12), SimInput.percentAny(12L))
        assertEquals(SimField.Set(12), SimInput.percentAny(12.0f)); assertEquals(SimField.Set(12), SimInput.percentAny(12.0))
        assertEquals(SimField.Ignore, SimInput.percentAny(12.5f)); assertEquals(SimField.Ignore, SimInput.percentAny("12"))
        assertEquals(SimField.Ignore, SimInput.percentAny(null)); assertEquals(SimField.Ignore, SimInput.percentAny(Double.NaN))
        assertEquals(SimField.Clear, SimInput.percentAny(-1)); assertEquals(SimField.Ignore, SimInput.percentAny(101))
        assertEquals(SimField.Set(150f), SimInput.rangeAny(150)); assertEquals(SimField.Set(150.5f), SimInput.rangeAny(150.5))
        assertEquals(SimField.Ignore, SimInput.rangeAny("150")); assertEquals(SimField.Clear, SimInput.rangeAny(-1))
        assertEquals(SimField.Set(EnergyMode.EV), SimInput.modeField("ev")); assertEquals(SimField.Clear, SimInput.modeField("clear"))
        assertEquals(SimField.Ignore, SimInput.modeField("bogus")); assertEquals(SimField.Ignore, SimInput.modeField(null))
    }
}
