package io.github.ilwoong.passvault.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** TST-08 (LOCK-03 트리거 판정) */
class AutoLockTest {

    private class Clock(var wall: Long = 1_000_000L, var elapsed: Long = 0L) : Clocks {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
        override fun bootCount() = 1
    }

    private val clock = Clock()
    private var timeoutSec = 60
    private var lockOnBackground = true
    private var locks = 0
    private var leaveLocks = 0
    private var clipboardClears = 0
    private val autoLock = AutoLock(
        clocks = clock,
        timeoutMs = { timeoutSec * 1_000L },
        lockOnBackground = { lockOnBackground },
        lock = { locks++ },
        lockOnLeave = { locks++; leaveLocks++ },
        clearClipboard = { clipboardClears++ },
    )

    @Test
    fun idleTimeoutLocksExactlyAtTheLimit() {
        clock.elapsed += 59_999
        autoLock.tick()
        assertEquals(0, locks)
        clock.elapsed += 1
        autoLock.tick()
        assertEquals(1, locks)
    }

    @Test
    fun interactionRestartsTheIdleTimer() {
        clock.elapsed += 50_000
        autoLock.onInteraction()
        clock.elapsed += 50_000
        autoLock.tick()
        assertEquals(0, locks)
    }

    @Test
    fun wallClockChangesDoNotAffectIdleTimer() {
        clock.wall -= 10 * 60_000 // 시간을 과거로
        clock.elapsed += 30_000
        autoLock.tick()
        assertEquals(0, locks)
        clock.wall += 24 * 60 * 60_000L // 시간을 미래로
        autoLock.tick()
        assertEquals("벽시계는 쓰지 않는다", 0, locks)
    }

    @Test
    fun backgroundLocksImmediatelyByDefault() {
        autoLock.onBackground()
        assertEquals(1, locks)
        assertEquals("붙여넣으러 나간 것이다 — 클립보드는 두고 간다", 0, clipboardClears)
    }

    @Test
    fun withoutImmediateBackgroundLockTheTimeInBackgroundCountsAsIdle() {
        lockOnBackground = false
        timeoutSec = 15
        autoLock.onBackground()
        assertEquals(0, locks)

        clock.elapsed += 10_000
        autoLock.onForeground()
        assertEquals("15초 전에 돌아왔다", 0, locks)

        clock.elapsed += 5_000
        autoLock.onForeground()
        assertEquals(1, locks)
    }

    @Test
    fun unlockingRestartsIdleSoBiometricUnlockAfterLongIdleDoesNotRelock() {
        clock.elapsed += 10 * 60_000 // 오래 방치 (이미 잠겨 있던 상태)
        autoLock.onUnlocked() // 화면을 건드리지 않는 생체 해제
        autoLock.tick()
        assertEquals(0, locks)
    }

    @Test
    fun screenOffLocksAndClearsClipboard() {
        autoLock.onScreenOff()
        assertEquals(1, locks)
        assertEquals(1, clipboardClears)
    }

    @Test
    fun idleLockDoesNotClearClipboard() {
        clock.elapsed += 60_000
        autoLock.tick()
        assertEquals(1, locks)
        assertEquals(0, clipboardClears)
    }

    @Test
    fun leavingDefersWhileBusyButIdleDoesNot() {
        autoLock.onBackground()
        autoLock.onScreenOff()
        assertEquals("백그라운드 전환과 화면 꺼짐은 전환이 끝난 뒤에라도 잠근다", 2, leaveLocks)

        clock.elapsed += 60_000
        autoLock.tick()
        assertEquals(3, locks)
        assertEquals("해제 전의 유휴는 미뤄 적용하지 않는다", 2, leaveLocks)
    }

    @Test
    fun settingsAreReadAtDecisionTime() {
        clock.elapsed += 20_000
        timeoutSec = 15 // 사용자가 설정을 줄였다
        autoLock.tick()
        assertEquals(1, locks)
    }
}

/** LOCK-03 예외: 우리가 띄운 파일 선택기 */
class AutoLockPickerTest {
    private var locks = 0
    private var elapsed = 0L
    private val autoLock = AutoLock(
        clocks = object : Clocks {
            override fun wallMs() = 0L
            override fun elapsedMs() = elapsed
            override fun bootCount() = 1
        },
        timeoutMs = { 60_000L },
        lockOnBackground = { true },
        lock = { locks++ },
        lockOnLeave = { locks++ },
        clearClipboard = {},
    )

    @Test
    fun pickerSuspendsOnlyTheImmediateBackgroundLock() {
        autoLock.onExternalPickerOpening()
        autoLock.onBackground()
        assertEquals(0, locks)

        elapsed += 61_000 // 선택기에서 오래 머물렀다
        autoLock.onForeground()
        assertEquals("유휴 판정은 그대로다", 1, locks)
    }

    @Test
    fun afterPickerClosesBackgroundLockResumes() {
        autoLock.onExternalPickerOpening()
        autoLock.onExternalPickerClosed()
        autoLock.onBackground()
        assertEquals(1, locks)
    }
}
