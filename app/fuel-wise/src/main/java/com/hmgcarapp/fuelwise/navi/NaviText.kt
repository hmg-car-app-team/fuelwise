package com.hmgcarapp.fuelwise.navi

import com.hmgcarapp.core.connection.ConnectionPolicy

/** 경유지/경로 요청 진행 상태(UI/보고에 그대로 표시: 성공한 척 금지). 문구는 사용자용 짧은 한국어. */
sealed interface NaviStatus {
    data object Idle : NaviStatus
    /** [id]: 요청 번호(늦은 타임아웃이 새 요청을 실패 처리하지 못하게). */
    data class Pending(val what: String, val id: Long = 0L) : NaviStatus
    /** [id]: 이 결과가 속한 요청 번호(0 = 번호 없음). 자기 요청의 결과만 기다리게 한다(C3). */
    data class Success(val what: String, val id: Long = 0L) : NaviStatus
    data class Failed(val reason: String, val id: Long = 0L) : NaviStatus
}

/** 내비 이벤트 -> 상태 전이(순수). 요청이 Pending 일 때만 상태를 바꾸고 관계없는 이벤트는 무시한다. */
object NaviReducer {
    fun onSuccess(cur: NaviStatus, msg: String): NaviStatus = if (cur is NaviStatus.Pending) NaviStatus.Success(msg, cur.id) else cur
    fun onError(cur: NaviStatus, codeName: String): NaviStatus = if (cur is NaviStatus.Pending) NaviStatus.Failed(NaviText.errorMessage(codeName), cur.id) else cur
    /** [id] 가 현재 Pending 요청과 같을 때만 타임아웃 처리(이전 요청의 타임아웃이 새 요청을 망치지 않게). */
    fun onTimeout(cur: NaviStatus, id: Long? = null): NaviStatus =
        if (cur is NaviStatus.Pending && (id == null || cur.id == id)) NaviStatus.Failed(NaviText.NO_RESPONSE, cur.id) else cur

    /** 연결 실패 등으로 요청 [id] 를 실패 처리할 때, 그 사이 다른 요청이 Pending 이 되었다면 그 상태를 덮지 않는다(P3). */
    fun failUnlessPending(cur: NaviStatus, msg: String, id: Long): NaviStatus = if (cur is NaviStatus.Pending) cur else NaviStatus.Failed(msg, id)

    /** 새 알림 시작·배너 소거 때 이전 요청의 낡은 결과(성공/실패)를 지운다. 처리 중(Pending)인 요청은 건드리지 않는다(N3). */
    fun settle(cur: NaviStatus): NaviStatus = if (cur is NaviStatus.Pending) cur else NaviStatus.Idle

    /** 요청 [id] 의 낡은 결과(성공/실패)만 지운다. 다른 요청의 결과나 처리 중(Pending) 요청은 건드리지 않는다. */
    fun settleFor(cur: NaviStatus, id: Long): NaviStatus = when {
        id == IGNORED -> cur
        cur is NaviStatus.Success && cur.id == id -> NaviStatus.Idle
        cur is NaviStatus.Failed && cur.id == id -> NaviStatus.Idle
        else -> cur
    }

    /** 요청 처리 중에는 새 요청을 받지 않는다(이벤트에 요청 ID 가 없어 겹치면 구분할 수 없다). */
    fun canStart(cur: NaviStatus): Boolean = cur !is NaviStatus.Pending

    /** 무시된 요청(처리 중이라 보내지 않음)을 나타내는 요청 번호. 실제 요청은 1 이상. */
    const val IGNORED = 0L

    /** [cur] 이 요청 [id] 의 최종 결과(성공/실패)면 그것을, 아니면(대기 중·다른 요청의 결과) null. 번호 없는(0) 결과는 어떤 요청에도 귀속하지 않는다. */
    fun resultFor(cur: NaviStatus, id: Long): NaviStatus? = when {
        id == IGNORED -> null
        cur is NaviStatus.Success && cur.id == id -> cur
        cur is NaviStatus.Failed && cur.id == id -> cur
        else -> null
    }

    /** 요청 시도 결과: 처리 중이면 [IGNORED], 아니면 새 번호. 번호는 단조 증가(1부터). */
    class Sequencer {
        private var last = 0L
        @Synchronized fun next(): Long = ++last
    }
}

object NaviText {
    const val NOT_CONNECTED = "요청에 실패했어요"
    /** 무응답은 사용자 문구가 없다(빈 문자열 = 표시·발화 안 함). 확정 실패만 [REQUEST_FAILED]/[NOT_CONNECTED]. */
    const val NO_RESPONSE = ""
    const val REQUEST_FAILED = "요청에 실패했어요"
    const val ROUTE_STARTED = "경로 안내를 시작했어요"
    const val WAYPOINT_ADDED = "경유지를 추가했어요"
    const val PENDING_ROUTE = "경로 요청 중"
    const val PENDING_VIA = "경유지 포함 경로 요청 중"
    const val PENDING_WAYPOINT = "경유지 추가 요청 중"
    const val BUSY = "요청에 실패했어요"

    /** 결과를 받지 못한 무응답(시간 초과·결과 없음)인지. 내비가 실제로 안내를 시작했을 수 있어 실패로 표시하지 않는다. */
    fun isNoResponse(result: NaviStatus?): Boolean = result == null || (result is NaviStatus.Failed && result.reason.isBlank())

    /** 음성 수락 뒤 결과 확인 발화(짧게). 결과를 못 받았으면(null) 응답 없음. */
    fun spoken(result: NaviStatus?): String = when (result) {
        is NaviStatus.Success -> result.what
        is NaviStatus.Failed -> result.reason
        else -> NO_RESPONSE
    }

    /** SDK 오류 코드 이름 -> 한국어. 원인별 사유는 보여 주지 않고 한 문장으로 통일한다(원시 코드는 로그로만). */
    @Suppress("UNUSED_PARAMETER")
    fun errorMessage(codeName: String): String = REQUEST_FAILED
}

/** 내비 연결 상수와 짧은 표시 단어. 연결 재생성 판단은 core `ManagedConnection` 이 한다. */
object NaviConnectRules {
    /** 연결 시도 후 이 시간 동안 콜백이 없으면 helper 를 새로 만든다. */
    const val STALL_MS = 10_000L

    /** 내비 요청 타임아웃이 이 횟수만큼 연속되면 helper 를 폐기해 새로 만든다(r6R5-7). */
    const val TIMEOUTS_TO_DISCARD = 2

    /** helper 연결 정책: 백오프 없이 즉시 재생성, 준비 정체 [STALL_MS], 요청 무응답 연속 [TIMEOUTS_TO_DISCARD] 회면 사망(테스트가 값을 고정한다). */
    val POLICY = ConnectionPolicy(
        backoffStartMs = 0L, backoffMaxMs = 0L, deathWaitsBackoff = false,
        readyTimeoutMs = STALL_MS, silenceCycles = TIMEOUTS_TO_DISCARD,
    )

    /** 사용자에게 보여 줄 짧은 단어(주행 중 한 줄에서 잘리지 않게 고정 길이). */
    fun shortWord(st: NaviStatus): String? = when (st) {
        NaviStatus.Idle -> null
        is NaviStatus.Pending -> "요청 중"
        is NaviStatus.Success -> "요청 완료"
        is NaviStatus.Failed -> if (st.reason.isBlank()) "결과 미확인" else "요청 실패"
    }
}
