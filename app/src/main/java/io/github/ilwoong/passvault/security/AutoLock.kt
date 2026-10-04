package io.github.ilwoong.passvault.security

/**
 * LOCK-03 잠금 트리거 판정. Android 이벤트 연결은 MainActivity·PassVaultApp 이 한다.
 *
 * 유휴 시간은 단조 시계로 잰다 — 시스템 시간을 바꿔 잠금을 피하지 못한다.
 * 백그라운드에 있던 시간도 상호작용이 없던 시간이므로 따로 재지 않는다.
 * [lock] 은 이미 잠겨 있으면 아무것도 하지 않는다 (SessionManager.lock).
 */
class AutoLock(
    private val clocks: Clocks,
    private val timeoutMs: () -> Long,
    private val lockOnBackground: () -> Boolean,
    private val lock: () -> Unit,
    private val clearClipboard: () -> Unit,
) {
    @Volatile
    private var lastInteraction = clocks.elapsedMs()

    /** LOCK-03 예외: 우리가 띄운 시스템 파일 선택기가 떠 있다. */
    @Volatile
    private var externalPickerOpen = false

    /** Activity.onUserInteraction. 화면을 보고만 있는 것은 상호작용이 아니다. */
    fun onInteraction() {
        lastInteraction = clocks.elapsedMs()
    }

    /** 해제 순간을 유휴 시작점으로 삼는다. 생체 해제는 화면을 건드리지 않는다. */
    fun onUnlocked() = onInteraction()

    /** ON_STOP (구성 변경 제외). 클립보드는 지우지 않는다 — 붙여넣으러 나간 것이다 (LOCK-04 6). */
    fun onBackground() {
        if (lockOnBackground() && !externalPickerOpen) lock()
    }

    /**
     * LOCK-03 예외: 백업용 시스템 파일 선택기(SAF)를 띄우기 직전에 부른다. 결과를 받으면
     * [onExternalPickerClosed]. 그 사이에는 백그라운드 즉시 잠금만 보류한다 — 유휴·화면 꺼짐은 그대로다.
     */
    fun onExternalPickerOpening() {
        externalPickerOpen = true
    }

    fun onExternalPickerClosed() {
        externalPickerOpen = false
    }

    /** ON_START. 화면을 그리기 전에 판정한다. */
    fun onForeground() = tick()

    /** 포그라운드에서 1초마다. */
    fun tick() {
        if (clocks.elapsedMs() - lastInteraction >= timeoutMs()) lock()
    }

    /** 화면 꺼짐은 "끝냈다"는 신호다. 클립보드도 지운다 (LOCK-04 6). */
    fun onScreenOff() {
        lock()
        clearClipboard()
    }
}
