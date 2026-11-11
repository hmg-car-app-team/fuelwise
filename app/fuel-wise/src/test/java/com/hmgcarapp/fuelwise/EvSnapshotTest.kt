package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.data.DataLabels
import com.hmgcarapp.fuelwise.data.SampleJsonParser
import com.hmgcarapp.fuelwise.data.StationData
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.CostEngine
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.EvalInput
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.Recommender
import com.hmgcarapp.fuelwise.domain.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvSnapshotTest {
    private fun asset(name: String): String =
        listOf("src/main/assets/$name", "fuel-wise/src/main/assets/$name").map(::File).first { it.exists() }.readText()

    private val ev get() = SampleJsonParser.parse(asset("ev_stations.json"), includeFuel = false)
    private val seoul = LatLon(37.5665, 126.9780)
    private val gangnam = LatLon(37.505, 127.0499)
    private val incheon = LatLon(37.4563, 126.7052)

    @Test fun realAsset_parses_1058_and_meta() {
        assertEquals(1058, ev.size)
        assertTrue(ev.all { it.kind == EnergyKind.EV && it.priceAssumed && it.pricePerKwh == 320 && it.waitMin == 0 })
        val m = SampleJsonParser.parseMeta(asset("ev_stations.json"))!!
        assertEquals("2026-11-11", m.collectedAt); assertEquals(320, m.assumedWon)
        assertTrue(m.license.contains("공공누리 제1유형")); assertTrue(m.source.contains("한국환경공단"))
    }

    @Test fun outOfRangeRows_ignored() {
        fun row(id: String, won: Int, kw: Double, wait: Int) = """{"id":"$id","name":"n","lat":37.5,"lon":127.0,"won":$won,"kw":$kw,"wait":$wait}"""
        val json = """{"_meta":{"source":"s","license":"l","collectedAt":"2026-11-11","priceAssumed":true,"assumedWon":320},"ev":[
            ${row("ok", 320, 50.0, 0)}, ${row("w0", 0, 50.0, 0)}, ${row("w2001", 2001, 50.0, 0)}, ${row("w2000", 2000, 50.0, 0)},
            ${row("k0", 320, 0.0, 0)}, ${row("k1001", 320, 1000.5, 0)}, ${row("k1000", 320, 1000.0, 0)}, ${row("kneg", 320, -5.0, 0)},
            ${row("t601", 320, 50.0, 601)}, ${row("t600", 320, 50.0, 600)}]}"""
        val ids = SampleJsonParser.parse(json, includeFuel = false).associateBy { it.id }
        assertEquals(setOf("ok", "w2000", "k1000", "t600", "t601"), ids.keys)
        assertEquals(600, ids["t601"]!!.waitMin) // 대기는 행 제외가 아니라 0..600 으로 보정
        assertTrue(ids.values.all { it.priceAssumed })
    }

    @Test fun missingOrBadSnapshot_fallsBackToSampleEv() {
        val sample = asset("stations_sample.json")
        for (bad in listOf(null, "not json", "{}", """{"ev":[]}""", """{"_meta":{"source":"s","license":"l","collectedAt":"d"},"ev":[]}""")) {
            val m = StationData.merge(sample, bad)
            assertNull(m.evMeta)
            assertEquals(5, m.candidates.count { it.kind == EnergyKind.EV })
            assertTrue(m.candidates.none { it.priceAssumed })
        }
        // _meta 없는 스냅샷은 출처를 표기할 수 없으므로 채택하지 않는다
        assertNull(StationData.merge(sample, """{"ev":[{"id":"x","name":"n","lat":37.5,"lon":127.0,"won":300,"kw":50.0}]}""").evMeta)
    }

    @Test fun merged_keepsSampleFuel_andReplacesEv() {
        val m = StationData.merge(asset("stations_sample.json"), asset("ev_stations.json"))
        assertNotNull(m.evMeta)
        assertEquals(10, m.candidates.count { it.kind == EnergyKind.FUEL })
        assertEquals(1058, m.candidates.count { it.kind == EnergyKind.EV })
        assertTrue(m.candidates.filter { it.kind == EnergyKind.FUEL }.all { it.fuelPrices.size == 4 && !it.priceAssumed })
    }

    @Test fun candidatesWithin20km_ofSeoulGangnamIncheon() {
        for (o in listOf(seoul, gangnam, incheon)) {
            val n = Recommender.nearby(ev, o).size
            assertTrue("후보 없음 $o", n > 0)
        }
        // 반경 60km 도 전체 선형 스캔으로 충분(1,058곳)
        assertTrue(Recommender.candidatesFor(ev, seoul, incheon).isNotEmpty())
    }

    @Test fun evaluation_works_on_snapshot_and_scan_isFast() {
        val c = ev
        val input = EvalInput(gangnam, null, EnergyKind.EV, Settings(), emptyList(), 40.0)
        val t0 = System.nanoTime()
        repeat(50) { Recommender.evaluate(input, Recommender.nearby(c, gangnam)) }
        val ms = (System.nanoTime() - t0) / 1_000_000 / 50
        assertTrue("평가 ${ms}ms", ms < 200)
        val q = Recommender.evaluate(input, Recommender.nearby(c, gangnam))
        assertTrue(q.isNotEmpty()); assertNotNull(Recommender.best(q))
    }

    @Test fun fast_isDecidedByKw_notSpeedLabel() {
        val raw = org.json.JSONObject(asset("ev_stations.json")).getJSONArray("ev")
        val byId = ev.associateBy { it.id }
        var labelMismatch = 0
        for (i in 0 until raw.length()) {
            val o = raw.getJSONObject(i); val c = byId.getValue(o.getString("id"))
            assertEquals(o.getDouble("kw") >= 50.0, SampleJsonParser.isFast(c))
            if ((o.getJSONObject("x").getString("speed") == "급속") != SampleJsonParser.isFast(c)) labelMismatch++
        }
        assertTrue("라벨과 kW 가 어긋나는 행이 있어야 이 테스트가 의미 있음", labelMismatch > 0)
        assertTrue(SampleJsonParser.isFast(Candidate("a", "a", "b", EnergyKind.EV, seoul, pricePerKwh = 1, chargerKw = 50.0)))
        assertFalse(SampleJsonParser.isFast(Candidate("a", "a", "b", EnergyKind.EV, seoul, pricePerKwh = 1, chargerKw = 49.9)))
    }

    @Test fun labels_branch_onRealVsSample() {
        val m = SampleJsonParser.parseMeta(asset("ev_stations.json"))
        assertEquals(
            "충전소: 공공데이터 스냅샷(한국환경공단 · 공공누리 제1유형 · 기준일 2026-11-11) · 요금은 가정 단가(원본에 가격 정보 없음) · 실시간 사용 가능 여부 아님 · 유가: 가공 샘플",
            DataLabels.full(m),
        )
        assertEquals("가공 샘플", DataLabels.full(null))
        assertEquals("가정 단가 320원/kWh", DataLabels.assumedUnit(m))
        assertFalse(DataLabels.full(m).contains("실시간 데이터"))
        assertTrue(DataLabels.ATTRIBUTION.contains("한국환경공단") && DataLabels.ATTRIBUTION.contains("공공누리 제1유형"))
        assertTrue(CostEngine.haversineKm(seoul, gangnam) > 0)
    }
}
