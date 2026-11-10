package com.hmgcarapp.fuelwise

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.fuelwise.app.Persisted
import com.hmgcarapp.fuelwise.app.ReportEntry
import com.hmgcarapp.fuelwise.app.StateCodec
import com.hmgcarapp.fuelwise.app.StateStore
import com.hmgcarapp.fuelwise.domain.EnergyKind
import com.hmgcarapp.fuelwise.domain.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** C6(저장 순서)·C7(읽기 오류)·M5(삭제 완전성)·C8(상한) - 임시 디렉터리의 실제 LocalJsonStore 로 검증. */
class StateStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "fuelwise_state"
    private fun dir() = File(tmp.root, "s").also { it.mkdirs() }
    private fun entry(ts: Long, n: String = "A") = ReportEntry(ts, n, EnergyKind.FUEL, 10.0, 1_000, 100)
    private fun withReports(vararg r: ReportEntry) = Persisted(settings = Settings(lowFuelPct = 35), report = r.toList(), selectedId = "x")
    private fun fileText() = File(dir(), "$name.json").readText()

    // ---- C7: 복원 분기 ----
    @Test fun restore_missing_isDefaults_andWritable() {
        val ss = StateStore(LocalJsonStore(dir()))
        val r = ss.restore(1_000)
        assertEquals(Persisted(), r.p); assertFalse(r.corrupt); assertFalse(r.readError); assertTrue(ss.writable)
    }

    @Test fun restore_ok_parsesSavedState() {
        val st = LocalJsonStore(dir()); val ss = StateStore(st)
        assertEquals(ss.writeLatest { withReports(entry(5)) }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        val r = StateStore(LocalJsonStore(dir())).restore(1_000)
        assertEquals(1, r.p.report.size); assertEquals(35, r.p.settings.lowFuelPct); assertFalse(r.corrupt)
    }

    @Test fun restore_corrupt_quarantinesAndStartsWithDefaults() {
        File(dir(), "$name.json").writeText("{not json")
        val ss = StateStore(LocalJsonStore(dir()))
        val r = ss.restore(1_000)
        assertTrue(r.corrupt); assertTrue(r.quarantined); assertEquals(Persisted(), r.p)
        assertTrue(File(dir(), "$name.json.corrupt").exists()); assertFalse(File(dir(), "$name.json").exists())
        assertTrue("손상은 격리 후 저장 가능", ss.writable)
        ss.writeLatest { Persisted() }
        assertFalse("정상 저장 성공 시 보존본 자동 삭제(core)", File(dir(), "$name.json.corrupt").exists())
    }

    @Test fun restore_readError_doesNotQuarantine_andHoldsWritesThisSession() {
        val original = File(dir(), "$name.json").also { it.mkdirs() } // 읽기만 실패하는 '파일'(디렉터리) = 일시 I/O 오류 모사
        val ss = StateStore(LocalJsonStore(dir()))
        val r = ss.restore(1_000)
        assertTrue(r.readError); assertFalse(r.corrupt); assertFalse(r.quarantined); assertEquals(Persisted(), r.p)
        assertFalse(ss.writable)
        assertEquals("저장 보류: 쓰지 않는다", com.hmgcarapp.fuelwise.app.WriteResult.HELD, ss.writeLatest { withReports(entry(1)) })
        assertTrue("원본은 그대로", original.exists()); assertFalse(File(dir(), "$name.json.corrupt").exists())
    }

    @Test fun clearAll_afterReadError_reenablesWriting() {
        File(dir(), "$name.json").mkdirs()
        val ss = StateStore(LocalJsonStore(dir())); ss.restore(1_000)
        assertFalse(ss.writable)
        ss.clearAll { Persisted(settings = Settings(lowFuelPct = 30)) }
        assertTrue(ss.writable); assertEquals(30, JSONObject(fileText()).getJSONObject("settings").getInt("lowFuelPct"))
    }

    // ---- M5: 시작 시 정리 / 전체 삭제 ----
    @Test fun restore_cleansAllTmpAtStartup_andPrunesOldCorruptOnly() {
        val d = dir()
        val tmpOld = File(d, "$name.json.111.tmp").also { it.writeText("x"); it.setLastModified(0) }
        val tmpNew = File(d, "$name.json.222.tmp").also { it.writeText("x"); it.setLastModified(10 * 60_000L + 1_000) }
        val corrupt = File(d, "$name.json.corrupt").also { it.writeText("x"); it.setLastModified(0) }
        StateStore(LocalJsonStore(d)).restore(10 * 60_000L + 1_000)
        assertFalse("10분 지난 tmp 삭제", tmpOld.exists()); assertFalse("시작 시점에는 최근 tmp 도 삭제(Fix-L)", tmpNew.exists()); assertTrue("7일 전이라 보존", corrupt.exists())
        StateStore(LocalJsonStore(d)).restore(LocalJsonStore.CORRUPT_RETENTION_MS)
        assertFalse("격리 7일 경과 삭제", corrupt.exists())
    }

    @Test fun clearAll_removesCorruptAndTmp_thenRewritesSettingsOnly() {
        val d = dir(); val ss = StateStore(LocalJsonStore(d))
        ss.writeLatest { withReports(entry(5), entry(6, "B")) }
        File(d, "$name.json.corrupt").writeText("old")
        File(d, "$name.json.999.tmp").writeText("x")
        ss.clearAll { withReports() }
        assertFalse(File(d, "$name.json.corrupt").exists()); assertFalse(File(d, "$name.json.999.tmp").exists())
        val o = JSONObject(fileText())
        assertEquals(0, o.getJSONArray("report").length()); assertEquals(35, o.getJSONObject("settings").getInt("lowFuelPct"))
    }

    // ---- C6: 쓰기 직전의 최신 상태 / 직렬화 ----
    @Test fun write_encodesLatestStateInsideLock_soStaleSnapshotCannotResurrectDeletedRecords() {
        val ss = StateStore(LocalJsonStore(dir()))
        val cur = java.util.concurrent.atomic.AtomicReference(withReports(entry(1), entry(2, "B")))
        val aInside = CountDownLatch(1); val aGo = CountDownLatch(1)
        val aDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val bSawADone = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val a = thread { ss.writeLatest { aInside.countDown(); aGo.await(); cur.get() }; aDone.set(true) }
        aInside.await()
        cur.set(withReports(entry(2, "B")))            // 사용자가 기록 A 를 삭제 -> 저장 요청 B
        val b = thread { ss.writeLatest { bSawADone.set(aDone.get()); cur.get() } }
        Thread.sleep(150)                            // B 가 락을 기다리는 동안에는 B 의 인코딩이 실행되지 않아야 한다
        assertNull("락 안에서만 인코딩(직렬화)", bSawADone.get())
        aGo.countDown(); a.join(); b.join()
        assertEquals(true, bSawADone.get())
        val names = JSONObject(fileText()).getJSONArray("report").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("name") } }
        assertEquals("삭제한 기록이 부활하지 않는다", listOf("B"), names)
    }

    @Test fun concurrentWrites_alwaysLeaveValidJsonOfSomeLatestState() {
        val ss = StateStore(LocalJsonStore(dir()))
        val n = java.util.concurrent.atomic.AtomicInteger(0)
        val ts = (0 until 4).map { thread { repeat(50) { ss.writeLatest { withReports(entry(n.incrementAndGet().toLong())) } } } }
        ts.forEach { it.join() }
        val back = StateCodec.parse(fileText())
        assertFalse(back.corrupt); assertEquals(1, back.p.report.size)
    }

    // ---- C8: 상한 ----
    @Test fun report_isCappedToMost500Recent() {
        val many = (1L..600L).map { entry(it) }
        val capped = StateCodec.capReport(many)
        assertEquals(500, capped.size); assertEquals(101L, capped.first().ts); assertEquals(600L, capped.last().ts)
        assertEquals("500 이하는 그대로", many.take(500), StateCodec.capReport(many.take(500)))
        // 디코드에서도 적용
        val arr = JSONArray().also { a -> many.shuffled().forEach { e -> a.put(JSONObject().put("ts", e.ts).put("name", "A").put("kind", "FUEL").put("qty", 1.0).put("spent", 1).put("saved", 1)) } }
        val d = StateCodec.decode(JSONObject().put("report", arr))
        assertEquals(500, d.report.size); assertEquals(101L, d.report.minOf { it.ts })
    }

    @Test fun cardsAndSelectedId_haveLengthLimits() {
        val longId = "x".repeat(StateCodec.MAX_ID + 1)
        val ok = "y".repeat(StateCodec.MAX_ID)
        val cards = JSONArray().also { a -> repeat(200) { a.put("c$it") }; a.put(longId) }
        val d = StateCodec.decode(JSONObject().put("cards", cards).put("selectedId", longId))
        assertEquals(StateCodec.MAX_CARDS, d.cards.size); assertNull(d.selectedId)
        assertEquals(ok, StateCodec.decode(JSONObject().put("selectedId", ok)).selectedId)
        assertEquals(setOf(ok), StateCodec.decode(JSONObject().put("cards", JSONArray().put(ok).put(longId))).cards)
    }

    @Test fun jsonNullSelectedId_isNull_withoutStringWorkaround() {
        assertNull(StateCodec.decode(JSONObject("""{"selectedId":null}""")).selectedId)
        assertNull(StateCodec.decode(JSONObject("""{}""")).selectedId)
        assertEquals("abc", StateCodec.decode(JSONObject("""{"selectedId":"abc"}""")).selectedId)
    }

    // ---- C11(d): 음성 응답 사용 설정 영속 ----
    @Test fun voiceReply_defaultsOn_andPersists() {
        assertTrue(Settings().voiceReply)
        assertTrue(StateCodec.decode(JSONObject("""{}""")).settings.voiceReply)
        val off = Persisted(settings = Settings(voiceReply = false))
        assertFalse(StateCodec.decode(StateCodec.encode(off)).settings.voiceReply)
        assertTrue(StateCodec.decode(StateCodec.encode(Persisted())).settings.voiceReply)
    }

    // ---- r9R4-1: 쓰기 실패 구분 ----
    private fun failingWriteDir() = dir().also { File(it, "$name.json").mkdirs(); File(File(it, "$name.json"), "child").writeText("x") } // 비어 있지 않은 디렉터리 위로는 이동 불가 = 쓰기 실패

    @Test fun writeFailure_reportsFailed_holdsWrites_andNeverTriesAgain() {
        val ss = StateStore(LocalJsonStore(failingWriteDir()))
        var calls = 0
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.FAILED, ss.writeLatest { calls++; Persisted(selectedId = "a") })
        assertFalse("실패 -> 이번 실행 저장 보류", ss.writable)
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.HELD, ss.writeLatest { calls++; Persisted(selectedId = "b") })
        assertEquals("보류 뒤에는 인코딩·쓰기 시도조차 없다", 1, calls)
    }

    @Test fun unchanged_isNotAFailure_andKeepsWritable() {
        val ss = StateStore(LocalJsonStore(dir()))
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.WROTE, ss.writeLatest { Persisted(selectedId = "a") })
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.UNCHANGED, ss.writeLatest { Persisted(selectedId = "a") })
        assertTrue(ss.writable)
    }

    @Test fun clearAll_afterWriteFailure_resumesSaving() {
        val d = failingWriteDir(); val ss = StateStore(LocalJsonStore(d))
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.FAILED, ss.writeLatest { Persisted(selectedId = "a") })
        File(File(d, "$name.json"), "child").delete() // 원인 해소(디스크 확보 모사)
        assertTrue(ss.clearAll { Persisted(selectedId = "kept") })
        assertTrue(ss.writable)
        assertEquals("kept", StateStore(LocalJsonStore(d)).restore(1_000).p.selectedId)
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.WROTE, ss.writeLatest { Persisted(selectedId = "next") })
    }
}
