package io.github.ilwoong.passvault.security

// LOCK-05: 해제 실패 백오프. 순수 함수.

/** 누적 실패 횟수 → 대기 ms. */
internal fun lockoutPenaltyMs(failedAttempts: Int): Long = when {
    failedAttempts < 5 -> 0
    failedAttempts == 5 -> 30_000
    failedAttempts == 6 -> 60_000
    failedAttempts == 7 -> 300_000
    else -> 900_000
}

/**
 * 남은 대기 ms. 0 이면 시도할 수 있다.
 *
 * 벽시계 기한과 (같은 부팅일 때만) 단조 시계 기한 중 더 늦은 쪽을 따른다.
 * 시계를 앞으로 돌려도 단조 시계 기한이 남는다. 재부팅 후에는 벽시계만 남는다 (수용한 한계).
 */
internal fun remainingLockoutMs(meta: VaultMeta, clocks: Clocks): Long {
    val byWall = meta.lockoutUntilEpochMs - clocks.wallMs()
    val byElapsed = if (meta.lockoutBootCount == clocks.bootCount()) {
        meta.lockoutUntilElapsedMs - clocks.elapsedMs()
    } else {
        0
    }
    return maxOf(byWall, byElapsed, 0)
}

internal fun VaultMeta.afterFailedUnlock(clocks: Clocks): VaultMeta {
    val attempts = failedAttempts + 1
    val penalty = lockoutPenaltyMs(attempts)
    return if (penalty == 0L) {
        withLockout(attempts, 0, 0, 0)
    } else {
        withLockout(attempts, clocks.wallMs() + penalty, clocks.bootCount(), clocks.elapsedMs() + penalty)
    }
}

internal fun VaultMeta.afterSuccessfulUnlock(): VaultMeta = withLockout(0, 0, 0, 0)

internal val VaultMeta.hasFailureState: Boolean
    get() = failedAttempts != 0 || lockoutUntilEpochMs != 0L || lockoutUntilElapsedMs != 0L
