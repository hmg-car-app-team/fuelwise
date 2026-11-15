package com.hmgcarapp.fuelwise.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapBaseTest {
    @Test fun parsesDeltaCoordinatesRingsTunnelsAndLabels() {
        val text = """
            # 주석은 건너뛴다
            R3 50500,24990,10,-5,-3,7
            R5t 100,200,1,1
            W 0,0,0,100,100,0,0,-100;20,20,10,0,0,10,-10,0
            X 1,2,3,4
            TQ 50500,24990 역삼1동
        """.trimIndent()
        val m = MapBase.parse(text)

        val road = m.byLayer.getValue(MapLayer.PRIMARY).single()
        assertFalse(road.tunnel)
        // 첫 점 = 기준 + 값, 이후 = 앞 점 + 차
        assertEquals(listOf(3_750_500, 12_704_990, 3_750_510, 12_704_985, 3_750_507, 12_704_992), road.pts.toList())
        assertEquals(3_750_500, road.minLat)
        assertEquals(12_704_992, road.maxLon)

        assertTrue(m.byLayer.getValue(MapLayer.TERTIARY).single().tunnel)

        val water = m.byLayer.getValue(MapLayer.WATER).single()
        assertEquals(2, water.rings.size)
        assertEquals(0 until 4, water.ringRange(0))
        assertEquals(4 until 8, water.ringRange(1))
        // 둘째 고리(구멍)도 첫 점은 기준 대비 값으로 다시 시작한다
        assertEquals(3_700_020, water.pts[8])

        val label = m.labels.single()
        assertEquals(MapLabel.Kind.QUARTER, label.kind)
        assertEquals("역삼1동", label.text)
        assertEquals(12_704_990, label.lonE5)
        // 모르는 줄 머리(X)는 무시한다
        assertEquals(3, m.shapes.size)
    }

    @Test fun bundledAssetCoversTheDemoAreaAndStaysInsideItsBbox() {
        val file = listOf("src/main/assets/${MapBase.ASSET}", "fuel-wise/src/main/assets/${MapBase.ASSET}").map(::File).first { it.exists() }
        val m = MapBase.parse(file.readText())
        listOf(MapLayer.MOTORWAY, MapLayer.PRIMARY, MapLayer.MINOR, MapLayer.PARK, MapLayer.WATER, MapLayer.BUILDING).forEach {
            assertTrue("$it 레이어가 비었다", m.byLayer[it].orEmpty().isNotEmpty())
        }
        // 생성기 bbox(37.34~37.57, 126.84~127.21). 경계를 넘는 선분의 바깥 끝점까지 0.05도 여유를 둔다.
        // 이보다 멀리 나간 좌표가 있으면 차분 부호화가 깨진 것(오차가 누적돼 멀리 흘러간다)
        m.shapes.forEach { s ->
            assertTrue(s.minLat >= 3_729_000 && s.maxLat <= 3_762_000)
            assertTrue(s.minLon >= 12_679_000 && s.maxLon <= 12_726_000)
        }
        // 기본 위치(역삼) 주변 지명과 경로 예시 목적지 주변 지명이 들어 있다
        val names = m.labels.map { it.text }.toSet()
        assertTrue("역삼동" in names)
        assertTrue("양재시민의숲" in names)
        assertTrue("탄천" in names)
        // 기본 위치(역삼)는 지도 안, 범위 밖(화성 남양)은 지도 없이 격자 바탕으로 그린다
        assertTrue(m.covers(com.hmgcarapp.fuelwise.domain.LatLon(37.505, 127.0499)))
        assertFalse(m.covers(com.hmgcarapp.fuelwise.domain.LatLon(37.205, 126.82)))
    }
}

