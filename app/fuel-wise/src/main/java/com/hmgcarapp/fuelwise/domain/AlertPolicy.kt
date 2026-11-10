package com.hmgcarapp.fuelwise.domain

/** 잔량 임계 알림 판단(순수). 한 번 울리면 충분히 회복(+히스테리시스)될 때까지 재알림하지 않는다. */
object AlertPolicy {
    const val REARM_MARGIN_PCT = 10
    const val REARM_RANGE_FACTOR = 1.5f

    data class Decision(val trigger: Boolean, val armed: Boolean, val reason: String?)

    /** 재무장 기준 잔량(%) = min(100, 임계 + 마진). 임계가 큰 값이어도 도달 불가능한 100 초과가 되지 않게 상한. */
    fun rearmLevelPct(thresholdPct: Int): Int = minOf(100, thresholdPct + REARM_MARGIN_PCT)

    /**
     * 신호가 null 이면 "회복됨"으로 보지 않는다: 알림 후(armed=false)에는 **값이 있는(non-null) 신호가 모두**
     * 회복 기준을 넘어야 재무장하고, 모든 신호가 null 이면 이전 armed 상태를 유지한다.
     */
    fun evaluate(levelPct: Int?, rangeKm: Float?, thresholdPct: Int, thresholdRangeKm: Int, armed: Boolean): Decision {
        val lowLevel = levelPct != null && levelPct <= thresholdPct
        val lowRange = rangeKm != null && rangeKm <= thresholdRangeKm
        val low = lowLevel || lowRange
        if (armed) {
            if (!low) return Decision(false, true, null)
            val why = buildList {
                if (lowLevel) add("잔량 ${levelPct}%")
                if (lowRange) add("주행가능거리 ${rangeKm!!.toInt()}km")
            }.joinToString(", ")
            return Decision(true, false, why)
        }
        if (levelPct == null && rangeKm == null) return Decision(false, false, null) // 신호 없음 -> 상태 유지
        val levelOk = levelPct == null || levelPct >= rearmLevelPct(thresholdPct)
        val rangeOk = rangeKm == null || rangeKm >= thresholdRangeKm * REARM_RANGE_FACTOR
        return if (levelOk && rangeOk) Decision(false, true, null) else Decision(false, false, null)
    }
}

/**
 * 음성 응답 해석(순수). 부분 문자열 매칭 금지: 정규화(소문자·문장부호 제거·공백 정리) 후
 * 거절 단어를 먼저 보고, 그 다음 **전체가 수락 표현(들)의 연속으로만 이루어졌는지** 확인한다.
 * 그래서 '예약', '응급', '네비' 처럼 문장 속 일부로 '예/응/네' 가 들어간 발화는 수락이 아니다.
 *
 * 방침(M3):
 * - '어'·'어어' 같은 머뭇거림은 수락이 아니다('어' 는 수락 표현에서 제외).
 * - 물음표가 붙은 의문형('네?', '응?')은 수락이 아니다. 물음표가 없는 '네'·'응'·'예' 단독 발화는 수락.
 * - 공백을 뺀 길이가 [MAX_COMPACT](14)자를 **초과**하면(15자부터) 수락 표현의 반복이어도 수락하지 않는다(긴 문장 차단; 14자는 허용).
 * - 경로 덮어쓰기 가능(`overwrite`=true, 경로 상태 UNKNOWN 알림)이면 '응/네/예/그래' 같은 단음절 수락을 허용하지 않고
 *   '바꿔줘' 같은 다음절 명시 표현만 수락한다.
 */
object VoiceIntent {
    const val MAX_COMPACT = 14

    /** 공백을 뺀 형태로 비교하는 수락 표현(짧고 명시적인 것만). '어' 는 머뭇거림이라 제외. */
    private val acceptPhrases = listOf(
        "응", "네", "넵", "예", "그래", "그래요", "좋아", "좋아요", "좋습니다", "오케이", "ok", "okay", "yes",
        "알겠어", "알겠어요", "알겠습니다", "추가해줘", "추가해줘요", "추가해주세요", "안내해줘", "안내해주세요", "안내해",
        "시작해줘", "시작해", "그렇게해줘", "그렇게해", "해줘", "해주세요", "부탁해", "부탁해요", "추가해",
    )

    /** 덮어쓰기 가능한 알림에서만 쓰는 다음절 명시 표현(단음절 수락 불가). */
    private val overwritePhrases = listOf(
        "바꿔줘", "바꿔줘요", "바꿔주세요", "바꿔", "바꿀게", "변경해줘", "변경해주세요", "그렇게해줘", "그렇게해주세요", "그렇게해",
    )

    /** 단어(공백 구분) 단위로 비교하는 거절 표현. */
    private val rejectWords = setOf(
        "아니", "아니요", "아뇨", "아냐", "싫어", "싫어요", "취소", "괜찮아", "괜찮아요", "괜찮습니다", "됐어", "됐어요",
        "말고", "나중에", "그만", "안돼", "안돼요",
    )

    /** 공백을 뺀 문자열에 포함되면 거절로 보는 구(부정 부사 '안' + 동사 등). */
    private val rejectCompact = listOf("안해", "하지마", "안할래")

    fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")

    fun isReject(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty()) return false
        if (n.split(' ').any { it in rejectWords }) return true
        val compact = n.replace(" ", "")
        return rejectCompact.any { compact.contains(it) }
    }

    fun isAccept(text: String, overwrite: Boolean = false): Boolean {
        val n = normalize(text)
        if (n.isEmpty() || isReject(text)) return false
        if (text.contains('?') || text.contains('？')) return false // 의문형('네?')은 확정이 아니다
        val compact = n.replace(" ", "")
        if (compact.length > MAX_COMPACT) return false
        val phrases = if (overwrite) overwritePhrases else acceptPhrases
        // compact 전체를 수락 표현의 연속으로 분할할 수 있어야 한다(DP)
        val ok = BooleanArray(compact.length + 1).also { it[0] = true }
        for (i in 1..compact.length) {
            ok[i] = phrases.any { ph -> i >= ph.length && ok[i - ph.length] && compact.regionMatches(i - ph.length, ph, 0, ph.length) }
        }
        return ok[compact.length]
    }

    /** 덮어쓰기 질문에 일반 수락 표현('네/응')으로만 답했는지: 수락으로 보지 않았음을 거절과 구분해 표시하기 위함(N4). */
    fun isPlainYesOnly(text: String): Boolean = !isAccept(text, overwrite = true) && isAccept(text, overwrite = false)
}
