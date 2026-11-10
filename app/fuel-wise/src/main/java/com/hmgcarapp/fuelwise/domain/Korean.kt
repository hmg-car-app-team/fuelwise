package com.hmgcarapp.fuelwise.domain

/**
 * 한국어 조사 선택(받침 판별). 후보명이 임의 문자열이므로 문구에 고정 조사를 쓰지 않는다.
 * 끝의 기호·공백(예: ")")은 건너뛰고 마지막 글자로 판단한다.
 * - 한글: 종성 유무 / 숫자: 영·일·삼·육·칠·팔 받침 있음
 * - 영문: **약어(전부 대문자이고 3자 이하, 예 LG·SK·Q)에만** 철자 읽기 규칙(l,m,n,r = 엘·엠·엔·알 받침)을 적용한다.
 *   소문자가 섞인 영어 단어(Shell, Charger)는 발음을 추정하지 않고 '중립'으로 본다([isNeutral]). 중립 이름에는
 *   조사를 붙이지 않는 것이 좋다(호출부가 문장을 재구성). [subject]/[topic]/[obj] 는 중립이면 '(이)가' 식 병기.
 */
object Josa {
    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun core(word: String) = word.trimEnd { !it.isLetterOrDigit() }

    /** 마지막이 소문자 섞인 영어 단어(약어 아님)라 받침을 알 수 없는 이름. */
    fun isNeutral(word: String): Boolean {
        val t = core(word)
        if (t.lastOrNull()?.let(::isLatin) != true) return false
        val run = t.takeLastWhile(::isLatin)
        return !(run.length <= 3 && run.all { it in 'A'..'Z' })
    }

    fun hasBatchim(word: String): Boolean {
        val c = core(word).lastOrNull() ?: return false
        return when {
            c in '가'..'힣' -> (c - '가') % 28 != 0
            c in '0'..'9' -> c in "013678"
            isLatin(c) -> !isNeutral(word) && c.lowercaseChar() in "lmnr"
            else -> false
        }
    }

    private fun pick(word: String, withB: String, withoutB: String, neutral: String) =
        word + if (isNeutral(word)) neutral else if (hasBatchim(word)) withB else withoutB

    /** 이/가 */
    fun subject(word: String) = pick(word, "이", "가", "(이)가")

    /** 은/는 */
    fun topic(word: String) = pick(word, "은", "는", "은(는)")

    /** 을/를 */
    fun obj(word: String) = pick(word, "을", "를", "을(를)")
}
