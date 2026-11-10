package com.hmgcarapp.fuelwise

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * 구조 보장 테스트. 과거 회귀(SDK 콜백 람다의 마지막 식이 value class `ChannelResult` 여서 언박싱 NPE)는 JVM 단위 테스트로 재현할 수 없다
 * (Android 런타임의 콜백 호출 경로에서만 발생). 이 파일은 NPE 를 재현하지 않고, 상태 갱신이 락·채널 없이
 * `MutableStateFlow.update` 의 원자적 읽기-수정-쓰기로 이뤄져 여러 스레드의 동시 콜백이 서로의 갱신을 잃지 않음을 검증한다.
 * (콜백이 Unit 을 반환하는 멤버 함수만 호출한다는 점은 EnergyPoller 가 `EnergyCallbacks`(Unit 반환)만 쓰는 구조와 컴파일로 고정된다.)
 */
class StateFlowStructureTest {
    /** 엔진의 실제 갱신 경로(EnergyEngine.book)에서도 서로 다른 도메인의 동시 갱신이 유실되지 않는다. */
    @Test fun engineBook_concurrentDomainUpdates_areBothKept() {
        val e = com.hmgcarapp.fuelwise.vehicle.EnergyEngine({ 1_000L })
        val ids = e.due().toMap()
        val start = CountDownLatch(1)
        val a = thread { start.await(); repeat(2000) { i -> e.fuelOk(ids.getValue(com.hmgcarapp.fuelwise.vehicle.Dom.FUEL), 10 + (i % 5)) } }
        val b = thread { start.await(); repeat(2000) { i -> e.evOk(ids.getValue(com.hmgcarapp.fuelwise.vehicle.Dom.EV), 20 + (i % 5)) } }
        val c = thread { start.await(); repeat(2000) { e.rangeOk(ids.getValue(com.hmgcarapp.fuelwise.vehicle.Dom.RANGE), 90_000f) } }
        start.countDown(); a.join(); b.join(); c.join()
        val r = e.book.value.reading()
        assertEquals(14, r.fuelPercent); assertEquals(24, r.evSocPercent); assertEquals(90f, r.rangeKm)
    }

    @Test fun latestStateWins_inOrderOfUpdates() {
        val e = com.hmgcarapp.fuelwise.vehicle.EnergyEngine({ 0L })
        val id = e.due().first { it.first == com.hmgcarapp.fuelwise.vehicle.Dom.FUEL }.second
        e.fuelOk(id, 10); e.fuelOk(id, 20); e.fuelOk(id, 30)
        assertEquals(30, e.book.value.fuel.value)
    }
}
