package com.hmgcarapp.fuelwise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 4차: 설정에는 연비·전비·탱크·배터리 용량 4행만 항상 노출하고, 나머지 고급 항목은 계속 숨긴다. */
class Round4RestoreTest {
    private val ui = File("src/main/java/com/hmgcarapp/fuelwise/ui/Screens.kt").readText()

    @Test fun fourVehicleRowsAlwaysShown_restStayHidden() {
        listOf("탱크 용량", "연비", "배터리 용량", "전비").forEach {
            assertTrue("$it 행은 플래그 없이 노출", Regex("""^\s*NumRow\("$it"""", RegexOption.MULTILINE).containsMatchIn(ui))
        }
        val hidden = Regex("""if \(SHOW_ADVANCED_SETTINGS\) NumRow\("([^"]+)"""").findAll(ui).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("차량 최대 충전 출력", "충전 목표", "EV 시간 환산", "주행가능거리 기준", "신호 없을 때 가정 잔량"), hidden)
        assertTrue(ui.contains("private const val SHOW_ADVANCED_SETTINGS = false"))
    }
}
