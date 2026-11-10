package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.domain.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** K5: 부분 문자열이 아니라 단어 경계·전체 일치로만 수락. */
class VoiceIntentTest {
    @Test fun shortExplicitAcceptances_areAccepted() {
        listOf("응", "네", "예", "좋아", "좋아요", "추가해줘", "추가해 줘", "안내해줘", "안내해 줘", "그래", "그래요", "네!", " 응. ", "OK", "yes", "응 추가해줘", "네 좋아요", "네네")
            .forEach { assertTrue(it, VoiceIntent.isAccept(it)) }
    }

    @Test fun wordsInsideOtherWords_areNotAcceptance() {
        // '예' '응' '네' '그래' 가 문장 속 일부일 뿐인 발화
        listOf("예약", "응급", "네비", "네비게이션 켜줘", "예쁘다", "그래서 어디야", "그래프", "응원해", "내일 날씨 알려줘", "저기 좀 봐", "라디오 켜줘", "추가 요금이 있나요")
            .forEach { assertFalse(it, VoiceIntent.isAccept(it)) }
    }

    @Test fun rejectionWins_overAcceptance() {
        listOf("아니", "아니요", "아니 좋아", "응 아니", "싫어", "취소", "괜찮아", "괜찮아요", "됐어", "나중에", "아니 말고", "응 안해", "하지마", "좋아 근데 취소")
            .forEach { assertFalse(it, VoiceIntent.isAccept(it)); assertTrue(it, VoiceIntent.isReject(it)) }
    }

    @Test fun emptyOrNoise_isNeither() {
        listOf("", "   ", "...", "음").forEach { assertFalse(it, VoiceIntent.isAccept(it)) }
        assertFalse(VoiceIntent.isReject("")); assertFalse(VoiceIntent.isReject("예약"))
    }

    @Test fun longSentences_areNotAccepted_evenIfTheyStartWithYes() {
        assertFalse(VoiceIntent.isAccept("응 근데 오늘 점심 뭐 먹을까 생각 중이야"))
        assertEquals("응 추가해 줘", VoiceIntent.normalize("  응, 추가해   줘!! "))
    }

    // ---- M3 ----
    @Test fun hesitation_isNotAcceptance() {
        listOf("어", "어어", "어어어", "어 응", "음 어").forEach { assertFalse(it, VoiceIntent.isAccept(it)) }
    }

    @Test fun questionForm_isNotAcceptance_butPlainConfirmationIs() {
        listOf("네?", "응?", "예?", "좋아?", "네 ?", "네？").forEach { assertFalse(it, VoiceIntent.isAccept(it)) }
        listOf("네", "응", "예", "네.", "응!").forEach { assertTrue(it, VoiceIntent.isAccept(it)) }
    }

    @Test fun overwriteAlert_acceptsOnlyMultiSyllableExplicitPhrases() {
        listOf("응", "네", "예", "그래", "넵", "좋아", "추가해줘", "안내해줘", "어", "응 응").forEach { assertFalse(it, VoiceIntent.isAccept(it, overwrite = true)) }
        listOf("바꿔줘", "바꿔", "바꿔 줘", "그렇게 해줘", "그렇게해", "바꿔주세요", "변경해줘").forEach { assertTrue(it, VoiceIntent.isAccept(it, overwrite = true)) }
        // 거절이 우선, 일반 모드에서는 기존 표현 그대로
        assertFalse(VoiceIntent.isAccept("바꾸지 말고 바꿔줘", overwrite = true))
        assertFalse(VoiceIntent.isAccept("안 바꿔 취소", overwrite = true))
        assertTrue(VoiceIntent.isAccept("응", overwrite = false)); assertFalse("일반 모드에서 '바꿔줘' 는 수락 표현이 아니다", VoiceIntent.isAccept("바꿔줘"))
    }

    /** 방침: 공백을 뺀 길이 14자까지는 수락 표현의 연속이면 수락, 15자부터는 수락하지 않는다(긴 문장 차단). */
    @Test fun lengthBoundary_14accepted_15rejected() {
        val c14 = "추가해주세요안내해주세요응응"
        val c15 = c14 + "응"
        assertEquals(14, c14.length); assertEquals(15, c15.length)
        assertTrue(VoiceIntent.isAccept(c14)); assertFalse(VoiceIntent.isAccept(c15))
        assertTrue("공백은 길이에 세지 않는다", VoiceIntent.isAccept("추가해주세요 안내해주세요 응 응"))
        assertFalse(VoiceIntent.isAccept("추가해주세요 안내해주세요 응 응 응"))
        assertEquals(VoiceIntent.MAX_COMPACT, 14)
    }

    @Test fun longSentence_isRejected() {
        assertFalse(VoiceIntent.isAccept("응 그런데 지금 가는 길에 있는 주유소가 더 싸지 않을까"))
        assertFalse(VoiceIntent.isAccept("네 알겠습니다 그렇게 해 주세요 감사합니다 수고하세요"))
    }
}
