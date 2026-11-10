package com.hmgcarapp.fuelwise.domain

/**
 * 알림 판단 보류 규칙(순수).
 * - [loadDone] = 후보 로드가 한 번이라도 **성공 완료**됨(후보가 0개여도 완료). 로드 전·로드 실패 중에는 판단을 보류하고 armed 를 소진하지 않는다
 *   (복원 직후 후보 로드 전 알림이 armed 를 소진해 이후 알림이 사라지던 문제).
 * - M4: 로드가 완료됐는데 추천할 후보가 없으면(강남 밖·반경 밖) 잔량 임계에서 **1회** 짧은 '후보 없음' 알림을 낸다(armed 소진, 수락/청취 없음).
 */
object AlertGate {
    const val NO_CANDIDATE_REASON = "잔량 부족"
    /** 실위치 수신을 기다리는 최대 시간(N1). 이후에는 기본 위치 기준임을 문구에 밝히고 알린다. */
    const val ORIGIN_TIMEOUT_MS = 20_000L

    /** [originReady] = 실위치를 받았거나 [ORIGIN_TIMEOUT_MS] 를 기다렸다. false 면 기본 위치(강남) 후보로 알리지 않고 보류한다(N1). */
    /** 잔량 판단([AlertPolicy.evaluate]) + 보류 규칙([hold]) 을 한 번에: 컨트롤러가 이 결과만 따른다(배선 회귀를 단위 테스트로 막음). */
    fun decide(
        levelPct: Int?, rangeKm: Float?, thresholdPct: Int, thresholdRangeKm: Int, armed: Boolean,
        loadDone: Boolean, hasBest: Boolean, originReady: Boolean,
    ): AlertPolicy.Decision =
        hold(AlertPolicy.evaluate(levelPct, rangeKm, thresholdPct, thresholdRangeKm, armed), loadDone, hasBest, armed, originReady)

    fun hold(d: AlertPolicy.Decision, loadDone: Boolean, hasBest: Boolean, currentArmed: Boolean, originReady: Boolean = true): AlertPolicy.Decision = when {
        !loadDone || !originReady -> AlertPolicy.Decision(false, currentArmed, null)
        d.trigger && !hasBest -> AlertPolicy.Decision(true, false, NO_CANDIDATE_REASON)
        else -> d
    }
}

/** 배너 수명 규칙(순수). */
object BannerRules {
    const val TTL_MS = 20_000L

    /** 음성 흐름이 끝났다고 볼 수 있는 상태(이때부터 자동 소거 타이머 시작). */
    fun isTerminal(voiceStatus: String) = voiceStatus != VoiceStatus.SPEAKING && voiceStatus != VoiceStatus.LISTENING

    /**
     * 앱 종료(release) 시: 알림 흐름이 끝나지 않았으면(말하는 중/듣는 중, 또는 배너가 뜨기 전 경로 확인 중인 [alertInFlight])
     * armed 를 true 로 되돌려 다음에 다시 알리게 한다.
     */
    fun armedOnRelease(voiceStatus: String?, armed: Boolean, alertInFlight: Boolean = false): Boolean =
        if (alertInFlight || voiceStatus == VoiceStatus.SPEAKING || voiceStatus == VoiceStatus.LISTENING) true else armed

    /** 재무장(false -> true)되면 배너를 치운다. */
    fun clearOnRearm(prevArmed: Boolean, newArmed: Boolean) = !prevArmed && newArmed

    /** 후보 목록이 바뀌어 배너가 가리키던 후보가 사라졌으면 candidateId 를 비운다(stale 방지). */
    fun staleSafe(candidateId: String?, currentIds: Collection<String>): String? = candidateId?.takeIf { it in currentIds }
}

/** 후보 로드 세대: 나중에 시작한 로드가 이미 반영됐다면 먼저 시작한 오래된 로드의 결과는 버린다. */
class LoadGeneration {
    private var issued = 0L
    private var applied = 0L
    fun next(): Long = ++issued
    /** 이 세대의 결과를 반영해도 되면 true(더 새로운 세대가 이미 반영됐으면 false). */
    fun tryApply(g: Long): Boolean { if (g <= applied) return false; applied = g; return true }
}

/** 경로 덮어쓰기 확인 팝업 규칙(순수, N2): 주행 중에는 떠 있을 수 없다('바꾸기'는 정차 전용이라 무동작이 된다). */
object ReplaceRules {
    fun <T> visible(confirm: T?, driving: Boolean): T? = if (driving) null else confirm
}

/** 음성 청취 결과 -> 배너 상태(순수). 빈 응답은 '무응답'. */
object VoiceOutcome {
    /** [canOverwrite] = 경로 덮어쓰기 가능한 알림(다음절 명시 표현만 수락). */
    fun of(text: String, canOverwrite: Boolean): String = when {
        VoiceIntent.normalize(text).isEmpty() -> VoiceStatus.NO_REPLY
        VoiceIntent.isAccept(text, overwrite = canOverwrite) -> VoiceStatus.ACCEPTED
        canOverwrite && VoiceIntent.isPlainYesOnly(text) -> VoiceStatus.NOT_ACCEPTED // '네/응' 만으로는 경로를 바꾸지 않았다(N4)
        else -> VoiceStatus.DECLINED
    }
}

/** 저장 복구·삭제 규칙(순수). */
object StoreRules {
    /** 저장 보류 안내(읽기 오류·쓰기 실패 공통; 토스트와 설정 화면이 같은 동사를 쓴다, r8R3-1·r9R4-1). */
    const val HELD_NOTICE = "저장하지 못해 이번에는 저장하지 않아요"
    const val HELD_RECOVERY = "앱을 다시 시작하거나 '기록 모두 삭제'를 하면 다시 저장해요."

    /**
     * 디스크에 쓸 armed(r9R5-1): 알림 음성 흐름이 끝나지 않았으면 true. 저장본 사본에만 적용하고 라이브 상태는 건드리지 않는다
     * (onStop->onStart 복귀 시 진행 중 알림이 영향받지 않게). [BannerRules.armedOnRelease] 와 같은 규칙이라 release() 와 겹쳐도 결과가 같다(멱등).
     */
    fun persistedArmed(voiceStatus: String?, armed: Boolean, alertInFlight: Boolean): Boolean =
        BannerRules.armedOnRelease(voiceStatus, armed, alertInFlight)

    /** 디스크에 쓸 armed 가 reduce 전후로 달라졌는지(순수): 달라졌을 때만 저장을 다시 예약해 보정본을 현재 값으로 교체한다. */
    fun persistedArmedChanged(before: Boolean, after: Boolean): Boolean = before != after

    /**
     * '기록 모두 삭제' 활성: 기록이 있거나, 저장 보류 중이거나, 손상 보존본이 남아 있을 때.
     * 복원이 끝나기 전([loading])에는 비활성: 복원 전 삭제는 디스크 원본을 지우지 못하고 대기 편집만 바꾸므로 거짓 성공이 된다(r7R4-2).
     */
    fun clearEnabled(reportCount: Int, saveHeld: Boolean, corruptKept: Boolean, loading: Boolean = false) =
        !loading && (reportCount > 0 || saveHeld || corruptKept)

    /**
     * '완료 기록' 성공 토스트(r8R1-3). 저장이 보류 중([saveHeld])이면 기록은 이번 실행의 메모리에만 있으므로 그렇게 밝힌다.
     * 보류가 아니면 기존 문구 그대로.
     */
    fun recordedMessage(name: String, netSaving: Long, saveHeld: Boolean): String {
        val saving = String.format(java.util.Locale.KOREA, "%,d", netSaving)
        return if (saveHeld) "기록됨(이번 실행에서만 유지): $name (추정 절약 ${saving}원)" else "기록됨: $name (추정 절약 ${saving}원)"
    }

    /** 삭제 실패 뒤 메모리 목록 복원(r7R4-1): 삭제 전 목록 + 그 사이 새로 생긴 항목. 저장 전체 상한은 호출부의 capReport 가 건다. */
    fun <T> restoreAfterFailedClear(before: List<T>, nowInMemory: List<T>): List<T> = before + nowInMemory.filter { it !in before }

    /** 설정 상단(세로 화면) 요약 한 줄: 저장 문제가 없으면 null. 자세한 안내는 '저장된 기록' 패널(r7R3-4). */
    fun summaryLine(saveHeld: Boolean, corruptKept: Boolean): String? = when {
        saveHeld && corruptKept -> "저장 문제: 읽기·쓰기 오류로 저장을 보류 중이고 손상된 파일이 남아 있어요. 아래 '저장된 기록'을 확인하세요."
        saveHeld -> "저장 문제: 읽기·쓰기 오류로 이번 세션은 저장하지 않아요. 아래 '저장된 기록'을 확인하세요."
        corruptKept -> "저장 문제: 손상된 저장 파일이 남아 있어요. 아래 '저장된 기록'을 확인하세요."
        else -> null
    }

    fun clearDescription(reportCount: Int, saveHeld: Boolean, corruptKept: Boolean): String = when {
        // 팝업 설명은 3줄에서 말줄임되므로 '되돌릴 수 없어요'가 잘리지 않게 짧게 유지한다(r6R3-1)
        !saveHeld && !corruptKept -> "기록 ${reportCount}건이 삭제돼요.\n되돌릴 수 없어요."
        reportCount > 0 -> "기록 ${reportCount}건과 저장 파일이 삭제돼요.\n되돌릴 수 없어요."
        else -> "저장 파일이 삭제돼요.\n되돌릴 수 없어요."
    }
}

/** 토스트 메시지 수명(순수): 주행 중에는 쌓아 두지 않는다(정차 뒤 낡은 안내가 뒤늦게 뜨는 것 방지). */
object MessageRules {
    fun onDriveChange(message: String?, nowDriving: Boolean): String? = if (nowDriving) null else message

    /** 새 토스트 설정: 주행 중에는 세팅하지 않고 기존 값을 유지한다(r6R3-3). */
    fun set(current: String?, new: String, driving: Boolean): String? = if (driving) current else new

    /**
     * 주행 중에도 잃으면 안 되는 안내(삭제 실패 등): 정차 중이면 바로 표시, 주행 중이면 [deferred] 에 보관한다.
     * 반환 = (표시할 메시지, 보관 중인 메시지). 기존 토스트 경로를 그대로 쓴다(새 UI 없음, r7R2-7/r7R5-8).
     */
    fun showOrDefer(current: String?, deferred: String?, new: String, driving: Boolean): Pair<String?, String?> =
        if (driving) current to new else new to deferred

    /** 정차로 바뀌었을 때 보관 중인 안내를 한 번 표시하고 비운다. 주행 중이면 그대로 둔다. */
    fun releaseDeferred(current: String?, deferred: String?, driving: Boolean): Pair<String?, String?> =
        if (driving || deferred == null) current to deferred else deferred to null
}

/** 예외 처리 규칙(순수): 취소와 VM 치명 오류(OOM 등)는 삼키지 않고 다시 던진다(r7R5-3/4, r7R2-6b). */
internal fun mustRethrow(t: Throwable): Boolean = t is kotlinx.coroutines.CancellationException || t is VirtualMachineError

/** 주행 화면 안내(순수): 차량 상태 신호(기어·UX)를 확인하지 못해 주행으로 간주 중이면 그 이유를 한 줄로(r7R3-2). */
object DriveNotice {
    const val UNVERIFIED = "차량 상태를 확인하지 못하면 주행 중으로 간주해요"
    fun of(gear: com.hmgcarapp.core.drive.Gear, uxRestricted: Boolean?): String? =
        if (gear == com.hmgcarapp.core.drive.Gear.UNKNOWN && uxRestricted == null) UNVERIFIED else null
}

/** 수집 본문의 예외 처리(순수): 취소·VirtualMachineError 는 재던지고, 그 외 Throwable 은 [onError] 로 넘긴다(r6R5-9). */
internal inline fun safelyCatching(onError: (Throwable) -> Unit, block: () -> Unit) {
    try { block() } catch (t: Throwable) { if (mustRethrow(t)) throw t; onError(t) }
}

/** 상세 패널의 내비 결과 줄은 배너가 이미 같은 결과를 보여 주면 생략한다(한 곳만 표시, r6R3-2). */
object NaviLineRules {
    fun showInDetail(bannerVisible: Boolean) = !bannerVisible
}
