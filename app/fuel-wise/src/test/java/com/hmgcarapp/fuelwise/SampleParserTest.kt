package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.data.SampleJsonParser
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.FuelGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** K10(d): 항목 단위 스킵 + 가격·좌표 범위 필터. */
class SampleParserTest {
    @Test fun badItems_areSkipped_goodOnesKept() {
        val json = """{"fuel":[
            {"id":"ok","name":"정상","brand":"A","lat":37.5,"lon":127.0,"gasoline":1700,"premium":1900},
            {"id":"nolat","name":"좌표없음","lat":"x","lon":127.0,"gasoline":1700},
            {"id":"lat91","name":"위도초과","lat":91.0,"lon":127.0,"gasoline":1700},
            {"id":"lon181","name":"경도초과","lat":37.5,"lon":181.0,"gasoline":1700},
            {"id":"zero","name":"가격0","lat":37.5,"lon":127.0,"gasoline":0},
            {"id":"huge","name":"가격과다","lat":37.5,"lon":127.0,"gasoline":999999},
            {"id":"neg","name":"음수","lat":37.5,"lon":127.0,"gasoline":-5},
            7,
            {"name":"id없음","lat":37.5,"lon":127.0,"gasoline":1700},
            {"id":"mix","name":"일부불량","lat":37.5,"lon":127.0,"gasoline":1650,"diesel":0,"lpg":"abc"}
          ],"ev":[
            {"id":"e1","name":"충전","lat":37.5,"lon":127.0,"won":300,"kw":100.0,"wait":5},
            {"id":"e0","name":"가격0","lat":37.5,"lon":127.0,"won":0,"kw":100.0},
            {"id":"ekw","name":"출력0","lat":37.5,"lon":127.0,"won":300,"kw":0.0},
            {"id":"ebig","name":"과다","lat":37.5,"lon":127.0,"won":99999,"kw":100.0},
            {"id":"e1","name":"중복id","lat":37.5,"lon":127.0,"won":310,"kw":50.0}
          ]}"""
        val c = SampleJsonParser.parse(json)
        assertEquals(listOf("ok", "mix", "e1"), c.map { it.id })
        assertEquals(mapOf(FuelGrade.GASOLINE to 1700, FuelGrade.PREMIUM to 1900), c[0].fuelPrices)
        assertEquals(mapOf(FuelGrade.GASOLINE to 1650), c[1].fuelPrices) // 불량 등급만 제거
        assertEquals(EnergyKind.EV, c[2].kind); assertEquals(5, c[2].waitMin)
    }

    @Test fun topLevelGarbage_throws_andMissingArrays_areEmpty() {
        var threw = false
        try { SampleJsonParser.parse("not json") } catch (e: Exception) { threw = true }
        assertTrue(threw)
        assertEquals(0, SampleJsonParser.parse("{}").size)
    }
}
