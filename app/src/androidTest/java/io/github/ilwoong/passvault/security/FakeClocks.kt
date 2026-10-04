package io.github.ilwoong.passvault.security

class FakeClocks(var wall: Long = 1_000_000_000L, var elapsed: Long = 50_000L, var boot: Int = 7) : Clocks {
    override fun wallMs() = wall
    override fun elapsedMs() = elapsed
    override fun bootCount() = boot
}
