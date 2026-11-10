package com.hmgcarapp.fuelwise

import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.fuelwise.app.Page
import com.hmgcarapp.fuelwise.app.Persisted
import com.hmgcarapp.fuelwise.app.ReportEntry
import com.hmgcarapp.fuelwise.app.StateStore
import com.hmgcarapp.fuelwise.data.FallbackStationRepository
import com.hmgcarapp.fuelwise.data.StationRepository
import com.hmgcarapp.fuelwise.domain.Candidate
import com.hmgcarapp.fuelwise.domain.DriveNotice
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.LatLon
import com.hmgcarapp.fuelwise.domain.MessageRules
import com.hmgcarapp.fuelwise.domain.Settings
import com.hmgcarapp.fuelwise.domain.StoreRules
import com.hmgcarapp.fuelwise.domain.mustRethrow
import com.hmgcarapp.fuelwise.domain.safelyCatching
import com.hmgcarapp.fuelwise.vehicle.Dom
import com.hmgcarapp.fuelwise.vehicle.EnergyCallbacks
import com.hmgcarapp.fuelwise.vehicle.EnergyEngine
import com.hmgcarapp.fuelwise.vehicle.EnergyPoller
import com.hmgcarapp.fuelwise.vehicle.EnergyPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Fix-I(라운드 7): 삭제 실패 복원, 안내 보류, 도메인별 끊김 래치, 예외 재던짐, 저장소 세대 검사. */
@OptIn(ExperimentalCoroutinesApi::class)
class FixITests {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "fuelwise_state"

    private fun entry(ts: Long, n: String = "A") = ReportEntry(ts, n, EnergyKind.FUEL, 10.0, 1000, 100)

    // ---- 삭제 실패 복원 / 버튼 / 요약 ----
    @Test fun restoreAfterFailedClear_keepsBeforeAndEntriesAddedMeanwhile_withoutDuplicates() {
        val a = entry(1); val b = entry(2, "B"); val c = entry(3, "C")
        assertEquals(listOf(a, b), StoreRules.restoreAfterFailedClear(listOf(a, b), emptyList()))
        assertEquals(listOf(a, b, c), StoreRules.restoreAfterFailedClear(listOf(a, b), listOf(c)))
        assertEquals(listOf(a, b), StoreRules.restoreAfterFailedClear(listOf(a, b), listOf(b)))
        assertEquals(listOf(c), StoreRules.restoreAfterFailedClear(emptyList(), listOf(c)))
    }

    @Test fun clearEnabled_disabledWhileLoading_evenIfHeldOrKept() {
        assertFalse(StoreRules.clearEnabled(3, saveHeld = true, corruptKept = true, loading = true))
        assertTrue(StoreRules.clearEnabled(3, saveHeld = false, corruptKept = false, loading = false))
        assertFalse(StoreRules.clearEnabled(0, false, false, loading = false))
    }

    @Test fun summaryLine_onlyWhenStorageProblem() {
        assertNull(StoreRules.summaryLine(false, false))
        assertTrue(StoreRules.summaryLine(true, false)!!.contains("저장하지 않아요"))
        assertTrue(StoreRules.summaryLine(false, true)!!.contains("손상"))
        assertTrue(StoreRules.summaryLine(true, true)!!.contains("보류"))
    }

    // ---- 안내 보류: 주행 중 -> 정차 시 한 번 ----
    @Test fun deferredMessage_isShownOnceWhenParked_andKeptWhileDriving() {
        // 주행 중 실패: 현재 토스트는 건드리지 않고 보관
        val (shown1, held1) = MessageRules.showOrDefer(null, null, "삭제 실패", driving = true)
        assertNull(shown1); assertEquals("삭제 실패", held1)
        // 아직 주행 중이면 그대로 보관
        assertEquals(null to "삭제 실패", MessageRules.releaseDeferred(null, held1, driving = true))
        // 정차로 바뀜: 한 번 표시하고 비움
        val (shown2, held2) = MessageRules.releaseDeferred(null, held1, driving = false)
        assertEquals("삭제 실패", shown2); assertNull(held2)
        // 다시 주행->정차: 이미 비었으므로 반복 표시 없음 (반대 방향)
        assertEquals(null to null, MessageRules.releaseDeferred(MessageRules.onDriveChange("삭제 실패", nowDriving = true), held2, driving = false))
        // 정차 중 실패는 즉시 표시, 보관 안 함
        assertEquals("삭제 실패" to null, MessageRules.showOrDefer(null, null, "삭제 실패", driving = false))
    }

    // ---- 예외 재던짐 ----
    @Test fun mustRethrow_cancellationAndVmErrorsOnly() {
        assertTrue(mustRethrow(CancellationException("c"))); assertTrue(mustRethrow(OutOfMemoryError())); assertTrue(mustRethrow(StackOverflowError()))
        assertFalse(mustRethrow(IllegalStateException())); assertFalse(mustRethrow(NoSuchMethodError())); assertFalse(mustRethrow(AssertionError()))
    }

    @Test fun safelyCatching_rethrowsVirtualMachineError_butCatchesOtherErrors() {
        var seen: Throwable? = null
        safelyCatching({ seen = it }) { throw NoClassDefFoundError("x") }
        assertTrue(seen is NoClassDefFoundError)
        var threw = false
        try { safelyCatching({ seen = null }) { throw OutOfMemoryError("x") } } catch (e: OutOfMemoryError) { threw = true }
        assertTrue(threw)
    }

    // ---- 주행 화면 안내 ----
    @Test fun driveNotice_onlyWhenGearUnknownAndUxUnknown() {
        assertEquals(DriveNotice.UNVERIFIED, DriveNotice.of(Gear.UNKNOWN, null))
        assertNull(DriveNotice.of(Gear.UNKNOWN, false)); assertNull(DriveNotice.of(Gear.UNKNOWN, true))
        assertNull(DriveNotice.of(Gear.DRIVE, null)); assertNull(DriveNotice.of(Gear.PARK, null))
    }

    private class MixedPort(val name: String, val log: MutableList<String>) : EnergyPort {
        override fun initialize() { log += "init $name" }
        override fun request(d: Dom, id: Int, cb: EnergyCallbacks) {
            when (d) {
                Dom.FUEL -> cb.error(Dom.FUEL, id, RuntimeException("DeadObjectException: service died")) // 연료만 서비스 사망 오류
                Dom.EV -> cb.ev(id, 40)
                Dom.RANGE -> cb.range(id, 80_000f)                                                        // 다른 도메인은 정상
            }
        }
        override fun release() { log += "release $name" }
    }

    @Test fun poller_otherDomainSuccess_doesNotHideDeadFuelDomain_portIsReplacedOnRetry() = runTest {
        val log = mutableListOf<String>(); var n = 0
        val eng = EnergyEngine({ testScheduler.currentTime })
        val poller = EnergyPoller({ n++; MixedPort("p$n", log) }, eng, 3_000)
        val job = launch { poller.run() }
        runCurrent()
        assertEquals(1, n)
        poller.retryNow(); runCurrent()
        assertEquals("연료 끊김이 EV/주행가능거리 정상 응답에 가려지지 않고 포트를 교체한다", 2, n)
        assertTrue(log.indexOf("release p1") in 0 until log.indexOf("init p2"))
        job.cancelAndJoin(); poller.closeQuietly()
    }

    // ---- 저장소: 삭제와 읽기 겹침 ----
    private fun dir() = File(tmp.root, "d").also { it.mkdirs() }

    @Test fun clearAll_failure_resetsDedupe_soSameContentIsRewrittenIfFileWasPartlyDeleted() {
        val d = dir()
        val ss = StateStore(LocalJsonStore(d))
        assertEquals(ss.writeLatest { Persisted(selectedId = "a") }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        val stuck = File(d, "$name.json.corrupt").also { it.mkdirs(); File(it, "child").writeText("x") } // .corrupt 삭제 불가
        assertFalse(ss.clearAll { Persisted(selectedId = "a") })
        assertFalse("상태 파일은 지워졌다", File(d, "$name.json").exists())
        stuck.deleteRecursively()
        assertEquals("같은 내용이어도 다시 기록된다", ss.writeLatest { Persisted(selectedId = "a") }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        assertTrue(File(d, "$name.json").exists())
    }

    // ---- 보안: 폴백 사유에 예외 메시지 금지 ----
    @Test fun fallbackReason_isClassNameOnly_noMessage() = runTest {
        val primary = object : StationRepository { override suspend fun candidates(origin: LatLon): List<Candidate> = throw IllegalStateException("https://x.example/?key=SECRET") }
        val fallback = object : StationRepository { override suspend fun candidates(origin: LatLon): List<Candidate> = emptyList() }
        val repo = FallbackStationRepository(primary, fallback)
        repo.candidates(LatLon(37.5, 127.0))
        assertEquals("IllegalStateException", repo.lastFallbackReason)
    }
}
