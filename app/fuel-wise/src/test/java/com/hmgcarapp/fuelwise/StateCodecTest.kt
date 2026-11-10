package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.app.Page
import com.hmgcarapp.fuelwise.app.Persisted
import com.hmgcarapp.fuelwise.app.ReportEntry
import com.hmgcarapp.fuelwise.app.StateCodec
import com.hmgcarapp.fuelwise.data.FallbackStationRepository
import com.hmgcarapp.fuelwise.data.RemoteStationRepository
import com.hmgcarapp.fuelwise.data.StationRepository
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EnergyMode
import com.hmgcarapp.fuelwise.domain.FuelGrade
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCodecTest {
    @Test fun roundTrip_preservesUserState() {
        val p = Persisted(
            settings = Settings(energyMode = EnergyMode.EV, fuelGrade = FuelGrade.DIESEL, tankL = 70, kmPerL = 9.5, lowSocPct = 25, destinationIndex = 1),
            page = Page.REPORT, cards = setOf("card_a"), selectedId = "f04",
            report = listOf(ReportEntry(1_700_000_000_000, "서초", EnergyKind.FUEL, 38.0, 62_890, 1350, true)),
            armed = false,
        )
        val back = StateCodec.decode(JSONObject(StateCodec.encode(p).toString()))
        assertEquals(p, back)
    }

    @Test fun corruptedOrMissing_fallsBackToDefaults() {
        assertEquals(Persisted(), StateCodec.decode(null))
        val partial = StateCodec.decode(JSONObject("""{"page":"BOGUS","settings":{"tankL":"x"}}"""))
        assertEquals(Page.CANDIDATES, partial.page)
    }

    @Test fun remote_requiresHttps() {
        var threw = false
        try { RemoteStationRepository("k", "http://insecure.example") } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun fallbackRepo_usesFakeWhenRemoteFails() = runBlocking {
        val fake = object : StationRepository { override suspend fun candidates(origin: LatLon) = listOf(Candidate("x", "x", "b", EnergyKind.FUEL, origin)) }
        val repo = FallbackStationRepository(RemoteStationRepository("k"), fake)
        assertEquals(1, repo.candidates(LatLon(0.0, 0.0)).size)
        assertNotNull(repo.lastFallbackReason)
    }

    @Test fun demoState_isNotPersisted_andLegacyKeysIgnored() {
        val p = Persisted(simFuel = 12, simSoc = 30, simRange = 33f, demoDrive = "DRIVING")
        val o = StateCodec.encode(p)
        listOf("simFuel", "simSoc", "simRange", "demoDrive").forEach { assertTrue(it, !o.has(it)) }
        val legacy = JSONObject("""{"simFuel":12,"simSoc":30,"simRange":33.0,"demoDrive":"DRIVING","armed":false,"settings":{"destinationIndex":0}}""")
        val back = StateCodec.decode(legacy)
        assertEquals(null, back.simFuel); assertEquals(null, back.simSoc); assertEquals(null, back.simRange); assertEquals(null, back.demoDrive)
        assertEquals(false, back.armed)                       // 일반 필드는 호환
        assertEquals(null, back.settings.destinationIndex)    // 옛 destinationIndex(기본 0 = 샘플 목적지)는 무시 -> 목적지 없음
    }

    @Test fun destination_roundTrip_andInvalidIgnored() {
        val ok = StateCodec.decode(StateCodec.encode(Persisted(settings = Settings(destinationIndex = 1))))
        assertEquals(1, ok.settings.destinationIndex)
        assertEquals(null, StateCodec.decode(JSONObject("""{"settings":{"destination":99}}""")).settings.destinationIndex)
    }

    @Test fun corruptedReportEntry_isSkipped_othersKept() {
        val o = JSONObject("""{"report":[
            {"ts":1700000000000,"name":"A","kind":"FUEL","qty":10.0,"spent":1000,"saved":100},
            {"ts":"abc","name":"B","kind":"FUEL","qty":1.0,"spent":1,"saved":1},
            {"name":"NoTs","kind":"FUEL","qty":1.0,"spent":1,"saved":1},
            {"ts":1700000001000,"name":"NaN","kind":"FUEL","qty":"NaN","spent":1,"saved":1},
            42,
            {"ts":1700000002000,"name":"C","kind":"BOGUS","qty":5.0,"spent":500,"saved":-20,"sample":true}
        ]}""")
        val r = StateCodec.decode(o).report
        assertEquals(listOf("A", "C"), r.map { it.name })
        assertEquals(EnergyKind.FUEL, r[1].kind)
        assertTrue(r[1].sample)
    }

    @Test fun outOfRangeNumbers_areClamped() {
        val o = JSONObject("""{"settings":{"tankL":99999,"kmPerL":0,"kmPerKwh":-4,"batteryKwh":"NaN","evMaxKw":0,"chargeTargetPct":500,"fallbackLevelPct":-3,"lowFuelPct":0,"lowSocPct":900,"lowRangeKm":1,"evTimeValueWonPerMin":-5},
            "report":[{"ts":1700000000000,"name":"A","kind":"FUEL","qty":-5.0,"spent":-100,"saved":99999999999999}]}""")
        val s = StateCodec.decode(o).settings
        assertEquals(120, s.tankL); assertEquals(3.0, s.kmPerL, 0.0); assertEquals(2.0, s.kmPerKwh, 0.0)
        assertEquals(Settings().batteryKwh, s.batteryKwh, 0.0)
        assertEquals(25, s.evMaxKw); assertEquals(100, s.chargeTargetPct); assertEquals(5, s.fallbackLevelPct)
        assertEquals(5, s.lowFuelPct); assertEquals(50, s.lowSocPct); assertEquals(10, s.lowRangeKm); assertEquals(0, s.evTimeValueWonPerMin)
        val e = StateCodec.decode(o).report.single()
        assertEquals(0.0, e.quantity, 0.0); assertEquals(0L, e.spent); assertEquals(1_000_000_000L, e.saved)
    }

    /** 키 화이트리스트: 기록 항목에는 이 키 외에 아무것도(좌표 등) 저장하지 않는다. */
    @Test fun encodedReportEntry_hasOnlyWhitelistedKeys() {
        val o = StateCodec.encode(Persisted(report = listOf(ReportEntry(1_700_000_000_000, "서초", EnergyKind.FUEL, 38.0, 62_890, 1350))))
        val e = o.getJSONArray("report").getJSONObject(0)
        assertEquals(setOf("ts", "name", "kind", "qty", "spent", "saved", "sample"), e.keys().asSequence().toSet())
        val top = o.keys().asSequence().toSet()
        assertEquals(setOf("settings", "page", "cards", "report", "armed"), top) // selectedId 는 null 이면 없음, 시연 상태·위치 없음
    }

    @Test fun simMode_isNotPersisted_andDoesNotTouchSettings() {
        val p = Persisted(settings = Settings(energyMode = EnergyMode.AUTO), simMode = EnergyMode.FUEL)
        val o = StateCodec.encode(p)
        assertTrue(!o.has("simMode"))
        assertEquals("AUTO", o.getJSONObject("settings").getString("energyMode"))
        assertEquals(null, StateCodec.decode(JSONObject("""{"simMode":"EV","settings":{"energyMode":"AUTO"}}""")).simMode)
    }

    @Test fun parse_distinguishesMissingFromCorrupt() {
        assertEquals(StateCodec.Restored(Persisted(), false), StateCodec.parse(null))
        assertTrue(StateCodec.parse("{not json").corrupt); assertTrue(StateCodec.parse("").corrupt); assertTrue(StateCodec.parse("[1,2]").corrupt)
        val ok = StateCodec.parse("""{"page":"REPORT"}""")
        assertTrue(!ok.corrupt); assertEquals(Page.REPORT, ok.p.page)
    }

    /** K12(f): selectedId / cards / armed 손상 입력. */
    @Test fun corruptSelectedCardsArmed_fallBackSafely() {
        val a = StateCodec.decode(JSONObject("""{"selectedId":123,"cards":"oops","armed":"yes"}"""))
        assertEquals("123", a.selectedId)          // org.json 은 숫자를 문자열로 강제: 존재하지 않는 id 는 화면에서 첫 후보로 대체됨
        assertEquals(emptySet<String>(), a.cards); assertEquals(true, a.armed) // 해석 불가 불리언은 기본값(대기)
        val b = StateCodec.decode(JSONObject("""{"selectedId":null,"cards":["card_a",5,null,{"x":1}],"armed":false}"""))
        assertEquals("JSON null 은 값 없음", null, b.selectedId); assertEquals(false, b.armed)
        assertTrue("card_a" in b.cards)
        assertEquals(null, StateCodec.decode(JSONObject("""{"selectedId":""}""")).selectedId)
        assertEquals(true, StateCodec.decode(JSONObject("""{"armed":null}""")).armed)
    }
}
