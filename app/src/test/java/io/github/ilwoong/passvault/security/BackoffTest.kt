package io.github.ilwoong.passvault.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClocks(var wall: Long = 1_000_000_000L, var elapsed: Long = 50_000L, var boot: Int = 7) : Clocks {
    override fun wallMs() = wall
    override fun elapsedMs() = elapsed
    override fun bootCount() = boot

    fun advance(ms: Long) {
        wall += ms
        elapsed += ms
    }
}

/** TST-08 (LOCK-05) */
class BackoffTest {

    private val clocks = FakeClocks()
    private val fresh = VaultMeta(ByteArray(SALT_BYTES), KdfParams(65536, 3, 2), ByteArray(60), null, 0, 0, 0)

    private fun failedTimes(n: Int): VaultMeta = (1..n).fold(fresh) { m, _ -> m.afterFailedUnlock(clocks) }

    @Test
    fun penaltyTableMatchesSpec() {
        assertEquals(listOf(0L, 0, 0, 0, 0, 30_000, 60_000, 300_000, 900_000, 900_000, 900_000),
            (0..10).map(::lockoutPenaltyMs))
    }

    @Test
    fun noWaitBeforeFifthFailure() {
        assertEquals(0, remainingLockoutMs(failedTimes(4), clocks))
    }

    @Test
    fun fifthFailureWaits30Seconds() {
        val m = failedTimes(5)
        assertEquals(5, m.failedAttempts)
        assertEquals(30_000, remainingLockoutMs(m, clocks))
        clocks.advance(29_999)
        assertEquals(1, remainingLockoutMs(m, clocks))
        clocks.advance(1)
        assertEquals(0, remainingLockoutMs(m, clocks))
    }

    @Test
    fun movingWallClockForwardDoesNotSkipWait() {
        val m = failedTimes(8) // 15분
        clocks.wall += 24 * 60 * 60 * 1000L
        assertEquals("같은 부팅이면 단조 시계 기한이 남는다", 900_000, remainingLockoutMs(m, clocks))
    }

    @Test
    fun movingWallClockBackwardOnlyMakesItLonger() {
        val m = failedTimes(5)
        clocks.wall -= 60_000
        assertEquals(90_000, remainingLockoutMs(m, clocks))
    }

    @Test
    fun appRestartKeepsWaitBecauseStateIsPersisted() {
        // 재시작 = 같은 메타를 다시 읽는다. VaultMetaStore 왕복으로 확인한다.
        val restored = VaultMetaStore.decode(VaultMetaStore.encode(failedTimes(6)))!!
        assertEquals(6, restored.failedAttempts)
        assertEquals(60_000, remainingLockoutMs(restored, clocks))
    }

    @Test
    fun clockChangePlusAppRestartStillBlockedWithinSameBoot() {
        val restored = VaultMetaStore.decode(VaultMetaStore.encode(failedTimes(7)))!!
        clocks.wall += 10 * 60 * 1000L
        assertEquals(300_000, remainingLockoutMs(restored, clocks))
    }

    @Test
    fun afterRebootOnlyWallClockCounts_knownLimitation() {
        val m = failedTimes(8)
        clocks.boot += 1
        clocks.elapsed = 10_000 // 재부팅으로 단조 시계가 다시 시작
        assertEquals("벽시계 기한은 그대로 지킨다", 900_000, remainingLockoutMs(m, clocks))
        clocks.wall += 900_000
        assertEquals("재부팅 + 시계 조작은 막지 못한다 (LOCK-05 수용 한계)", 0, remainingLockoutMs(m, clocks))
    }

    @Test
    fun eachFurtherFailureRestartsTheWaitFromNow() {
        var m = failedTimes(5)
        clocks.advance(30_000)
        m = m.afterFailedUnlock(clocks)
        assertEquals(60_000, remainingLockoutMs(m, clocks))
    }

    @Test
    fun successResetsEverything() {
        val m = failedTimes(8).afterSuccessfulUnlock()
        assertEquals(0, m.failedAttempts)
        assertEquals(0, remainingLockoutMs(m, clocks))
        assertFalse(m.hasFailureState)
        assertTrue(failedTimes(1).hasFailureState)
    }

    @Test
    fun lockoutDoesNotTouchKeyMaterial() {
        val m = failedTimes(8)
        assertTrue(m.wrappedVkByMk === fresh.wrappedVkByMk)
        assertTrue(m.kdfSalt === fresh.kdfSalt)
        assertEquals(fresh.kdfParams, m.kdfParams)
    }
}
