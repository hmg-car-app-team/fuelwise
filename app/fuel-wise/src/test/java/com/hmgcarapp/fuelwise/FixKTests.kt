package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.app.Persisted
import com.hmgcarapp.fuelwise.app.ReportEntry
import com.hmgcarapp.fuelwise.app.StateCodec
import com.hmgcarapp.fuelwise.data.DataLabels
import com.hmgcarapp.fuelwise.data.SampleJsonParser
import com.hmgcarapp.fuelwise.data.StationData
import com.hmgcarapp.fuelwise.domain.*
import com.hmgcarapp.fuelwise.navi.RoutePlans
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class FixKTests {
    private val tz = TimeZone.getTimeZone("UTC")
    private fun ev(id: String, extra: String = "") = """{"id":"$id","name":"충전 $id","brand":"B","lat":37.4,"lon":127.0,"won":320,"kw":100.0$extra}"""
    private fun snap(meta: String, vararg rows: String) = """{"_meta":{"source":"s","license":"l","collectedAt":"2026-11-11"$meta},"ev":[${rows.joinToString(",")}]}"""

    @Test fun snapshotEv_defaultsToAssumed_unlessExplicitFalse() {
        val j = snap("", ev("a"), ev("b", ""","x":{"priceAssumed":false}"""), ev("c", ""","x":{"priceAssumed":true}"""))
        val by = SampleJsonParser.parse(j, includeFuel = false).associateBy { it.id }
        assertTrue(by["a"]!!.priceAssumed); assertFalse(by["b"]!!.priceAssumed); assertTrue(by["c"]!!.priceAssumed)
        val m = StationData.merge("""{"fuel":[],"ev":[]}""", j)
        assertTrue(m.candidates.filter { it.id == "a" }.all { it.priceAssumed })
        // 가공 샘플 충전소는 기본값 그대로(가정 아님)
        assertFalse(SampleJsonParser.parse("""{"ev":[${ev("s")}]}""").single().priceAssumed)
    }

    @Test fun assumedWon_zeroOrMissing_fallsBackTo320() {
        assertEquals(320, SampleJsonParser.parseMeta(snap(""","assumedWon":0""", ev("a")))!!.assumedWon)
        assertEquals(320, SampleJsonParser.parseMeta(snap("", ev("a")))!!.assumedWon)
        assertEquals(320, SampleJsonParser.parseMeta(snap(""","assumedWon":-5""", ev("a")))!!.assumedWon)
        assertEquals(350, SampleJsonParser.parseMeta(snap(""","assumedWon":350""", ev("a")))!!.assumedWon)
    }

    private fun q(id: String, unit: Double, km: Double, assumed: Boolean = false, base: Boolean = false, net: Long = 0, name: String = id) = Quote(
        Candidate(id, name, "B", EnergyKind.EV, LatLon(0.0, 0.0), pricePerKwh = 320, chargerKw = 100.0, priceAssumed = assumed),
        320.0, unit, null, 40.0, 12_800, km, 0, 12_800, 0.0, 0.0, 0, 12_800, net, 0, base,
    )

    @Test fun priceSort_tieBreaks_detourThenId() {
        val l = listOf(q("c", 320.0, 3.0), q("b", 320.0, 1.0), q("a", 320.0, 3.0), q("z", 300.0, 9.0))
        assertEquals(listOf("z", "b", "a", "c"), Recommender.sort(l, SortKey.PRICE).map { it.candidate.id })
    }

    @Test fun report_assumedEntries_excludedFromSavedAndFlagged() {
        val t = 1_700_000_000_000L; val key = ReportLogic.monthKey(t, tz)
        val items = listOf(
            ReportEntry(t, "A", EnergyKind.FUEL, 10.0, 1000, 500),
            ReportEntry(t + 1, "B", EnergyKind.EV, 10.0, 3200, 0, assumed = true),
            ReportEntry(t + 2, "C", EnergyKind.EV, 10.0, 3200, 999, assumed = true),
        )
        assertEquals(500L, ReportLogic.monthSaved(items, key, tz)); assertEquals(500L, ReportLogic.totalSaved(items))
        assertEquals(7400L, ReportLogic.monthSpent(items, key, tz)); assertEquals(2, ReportLogic.monthAssumedCount(items, key, tz))
    }

    @Test fun codec_assumedRoundTrip_andOldFilesReadFalse() {
        val e = ReportEntry(1_700_000_000_000, "충전", EnergyKind.EV, 10.0, 3200, 0, assumed = true)
        val o = StateCodec.encode(Persisted(report = listOf(e, e.copy(ts = 1_700_000_100_000, assumed = false))))
        val a = o.getJSONArray("report")
        assertTrue(a.getJSONObject(0).getBoolean("assumed")); assertFalse(a.getJSONObject(1).has("assumed")) // 새 키는 선택적
        assertEquals(listOf(true, false), StateCodec.decode(o).report.map { it.assumed })
        val old = JSONObject("""{"report":[{"ts":1700000000000,"name":"x","kind":"EV","qty":1.0,"spent":10,"saved":5}]}""")
        assertFalse(StateCodec.decode(old).report.single().assumed)
    }

    @Test fun recordedToast_assumed_hasNoSavingAndNoAssumptionNote() {
        val m = StoreRules.recordedMessage("충전소", 1350, false, assumed = true)
        assertEquals("기록됨: 충전소", m); assertFalse(m.contains("절약")); assertFalse(m.contains("가정")); assertFalse(m.contains("실제 요금"))
        assertTrue(StoreRules.recordedMessage("충전소", 1350, true, assumed = true).startsWith("기록됨: 충전소"))
        assertEquals("기록됨: 주유소", StoreRules.recordedMessage("주유소", 1350, false))
    }

    @Test fun alertText_assumed_neverClaimsSaving_andBannerFits() {
        for (best in listOf(q("a", 320.0, 2.5, assumed = true, net = 800), q("a", 320.0, 2.5, assumed = true, net = -3), q("a", 320.0, 2.5, assumed = true, base = true))) {
            val v = AlertText.voice("잔량 15%", best, "충전", RoutePlans.ASK_VIA)
            assertFalse(v, v.contains("가정 단가") || v.contains("절약") || v.contains("실질"))
            val line = AlertText.line("잔량 15%, 주행가능거리 40km", best)
            assertTrue(line, line.length <= AlertText.BANNER_MAX && !line.contains("절약") && !line.contains("가정 단가"))
        }
        assertEquals("잔량 15% · 충전 a", AlertText.line("잔량 15%", q("a", 320.0, 2.5, assumed = true, name = "충전 a")))
        // 주유소(비가정) 기존 문구 불변
        assertTrue(AlertText.line("잔량 15%", q("f", 1700.0, 2.0, net = 1200)).endsWith("약 1,200원 절약"))
    }

    @Test fun assumedCard_totalMatchesDiscountedUnit_notListUnit() {
        val c = Candidate("e1", "충전 e1", "B", EnergyKind.EV, LatLon(37.4, 127.0), pricePerKwh = 320, chargerKw = 100.0, priceAssumed = true)
        val d = Presets.discounts.first { it.id == "ev_member" }
        val r = Recommender.evaluate(EvalInput(LatLon(37.4, 127.0), null, EnergyKind.EV, Settings(), listOf(d), 40.0), listOf(c)).single()
        assertEquals(320.0, r.listUnitPrice, 0.0); assertEquals(288.0, r.unitPrice, 0.0)
        assertEquals(Math.round(r.unitPrice * 40.0), r.fillCost) // 합계(주입비)는 할인 적용 단가 기준 -> 카드는 '가정 단가 → 할인 적용' 을 함께 표기
    }

    @Test fun fuelDisplay_unchanged_noAssumedFlag() {
        val f = Candidate("f", "주유소", "A", EnergyKind.FUEL, LatLon(0.0, 0.0), fuelPrices = mapOf(FuelGrade.GASOLINE to 1700))
        assertFalse(f.priceAssumed)
        assertTrue(AlertText.voice("잔량 15%", q("f", 1700.0, 2.0, net = 1350), "주유", RoutePlans.ASK_VIA).contains("실질 1,350원 절약"))
    }
}
