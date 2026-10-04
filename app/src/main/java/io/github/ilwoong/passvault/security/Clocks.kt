package io.github.ilwoong.passvault.security

/**
 * LOCK-03, LOCK-05 에 쓰는 시계들. Android 구현은 di/ 에 있다 (ARC-03).
 */
interface Clocks {
    /** 벽시계. 사용자가 바꿀 수 있다. */
    fun wallMs(): Long

    /** 단조 시계 (`SystemClock.elapsedRealtime`). 사용자가 바꿀 수 없지만 재부팅하면 0 부터 다시 센다. */
    fun elapsedMs(): Long

    /** 부팅 번호 (`Settings.Global.BOOT_COUNT`). 단조 시계 값이 같은 부팅의 것인지 가린다. */
    fun bootCount(): Int
}
