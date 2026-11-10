package com.hmgcarapp.fuelwise

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.fuelwise.app.Persisted
import com.hmgcarapp.fuelwise.app.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Refactor-B: 저장 상태 전이 표(세션 중 재읽기 없음). 시작 상태별로 (writable, corrupt 파일, json) 가 어떻게 바뀌는지 고정한다.
 * | 시작       | 복원 직후                         | 저장                      | clearAll                |
 * | 정상       | writable                          | 기록                      | 성공 -> 초기화·writable |
 * | 손상       | .corrupt 격리·writable             | 기록 + .corrupt 삭제       | 성공                    |
 * | 읽기 오류  | 보류(writable=false)·원본 불변     | 저장 안 함(재시작 전까지)   | 성공 -> 재개 / 실패 -> 보류 유지 |
 */
class StateTransitionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "fuelwise_state"
    private fun dir() = File(tmp.root, "t").also { it.mkdirs() }
    private fun json() = File(dir(), "$name.json")
    private fun corrupt() = File(dir(), "$name.json.corrupt")
    private fun store() = StateStore(LocalJsonStore(dir()))

    @Test fun normal_restoreThenSaveThenClear() {
        val ss = store()
        assertEquals(ss.writeLatest { Persisted(selectedId = "a") }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        val ss2 = store(); val r = ss2.restore(1_000)
        assertEquals("a", r.p.selectedId); assertFalse(r.corrupt || r.readError); assertTrue(ss2.writable)
        assertTrue(ss2.clearAll { Persisted(selectedId = "z") }); assertTrue(ss2.writable)
        assertEquals("z", store().restore(2_000).p.selectedId)
    }

    @Test fun corrupt_quarantined_writable_andSavingRemovesCorruptFile() {
        json().writeText("{not json")
        val ss = store(); val r = ss.restore(1_000)
        assertTrue(r.corrupt); assertTrue(r.quarantined); assertTrue(ss.writable)
        assertTrue(corrupt().exists())
        assertEquals(ss.writeLatest { Persisted(selectedId = "n") }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        assertFalse("정상 저장 뒤 .corrupt 삭제", corrupt().exists())
        assertEquals("n", store().restore(2_000).p.selectedId)
    }

    @Test fun readError_holdsWrites_forWholeSession_withoutRereading() {
        json().mkdirs() // 읽기 오류
        val ss = store(); val r = ss.restore(1_000)
        assertTrue(r.readError); assertFalse(ss.writable)
        json().deleteRecursively(); File(dir(), "$name.json").writeText(com.hmgcarapp.fuelwise.app.StateCodec.encode(Persisted(selectedId = "disk")).toString()) // 오류가 사라져도
        assertEquals("같은 세션에서는 저장하지 않는다", com.hmgcarapp.fuelwise.app.WriteResult.HELD, ss.writeLatest { Persisted(selectedId = "mem") })
        assertFalse(ss.writable)
        assertEquals("원본 불변", "disk", store().restore(2_000).p.selectedId) // 앱 재시작 = 새 StateStore 가 정상 읽기
    }

    @Test fun readError_clearAllSuccess_resumesWrites() {
        json().mkdirs()
        val ss = store(); ss.restore(1_000)
        assertTrue(ss.clearAll { Persisted(selectedId = "after") })
        assertTrue(ss.writable)
        assertEquals(ss.writeLatest { Persisted(selectedId = "next") }, com.hmgcarapp.fuelwise.app.WriteResult.WROTE)
        assertEquals("next", store().restore(2_000).p.selectedId)
    }

    @Test fun readError_clearAllFailure_keepsHold() {
        json().mkdirs(); File(json(), "child").writeText("x") // 비어 있지 않은 디렉터리: 삭제 실패
        val ss = store(); ss.restore(1_000)
        assertFalse(ss.clearAll { Persisted() })
        assertFalse(ss.writable)
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.HELD, ss.writeLatest { Persisted(selectedId = "x") })
    }

    @Test fun restoreFailure_holdWrites_blocksSave() {
        val ss = store(); ss.holdWrites()
        assertEquals(com.hmgcarapp.fuelwise.app.WriteResult.HELD, ss.writeLatest { Persisted(selectedId = "x") }); assertFalse(json().exists())
    }
}
