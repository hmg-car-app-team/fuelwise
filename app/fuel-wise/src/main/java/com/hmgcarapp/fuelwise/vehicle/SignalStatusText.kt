package com.hmgcarapp.fuelwise.vehicle

/** 차량 신호 상태 패널에 보이는 문구. 릴리스에는 고정 문구만, debuggable 빌드에서만 SDK 진단 원문을 그대로 쓴다. */
enum class SignalStatus { PENDING, UNSUPPORTED, NO_VALUE, ERROR }

object SignalStatusText {
    const val UNSUPPORTED = "이 차량에서는 제공되지 않아요"
    const val NO_VALUE = "값이 아직 없어요"
    const val ERROR = "읽지 못했어요"

    /** 진단 문자열(표시 전용)을 상태로 분류한다. 앱 판단(확정 여부)은 문자열이 아니라 값/오류 존재 여부만 쓴다. */
    fun classify(rawError: String?): SignalStatus {
        if (rawError == null) return SignalStatus.PENDING
        val s = rawError.lowercase()
        return when {
            "not supported" in s || "not in carpropertyconfig" in s -> SignalStatus.UNSUPPORTED
            "값 없음" in rawError || "신호 소실" in rawError -> SignalStatus.NO_VALUE
            else -> SignalStatus.ERROR
        }
    }

    /** 값이 없을 때의 표시 문구. [debuggable] 이면 기존 원문 진단("없음: ...")을 유지한다. */
    fun missing(rawError: String?, debuggable: Boolean): String {
        if (debuggable) return "없음: ${rawError ?: "대기"}"
        return when (classify(rawError)) {
            SignalStatus.UNSUPPORTED -> UNSUPPORTED
            SignalStatus.PENDING, SignalStatus.NO_VALUE -> NO_VALUE
            SignalStatus.ERROR -> ERROR
        }
    }
}
