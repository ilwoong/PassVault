package io.github.ilwoong.passvault.security

import com.lambdapioneer.argon2kt.Argon2Exception
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRY-09 결정 순서. 실기기에서는 메모리 할당 실패 경로를 재현할 수 없으므로
 * 측정 함수를 가짜 기기로 바꿔 모든 분기를 확인한다.
 */
class KdfCalibrationTest {

    private val requested = mutableListOf<KdfParams>()

    /**
     * 걸리는 시간이 m·t 에 비례하는 가짜 기기.
     * [msPerIterAt64MiB] 는 64 MiB, t = 1 일 때의 ms.
     */
    private fun device(
        msPerIterAt64MiB: Double,
        maxAllocKiB: Int = Int.MAX_VALUE,
        allocFailure: () -> Throwable = { Argon2Exception("ARGON2_MEMORY_ALLOCATION_ERROR") },
    ): (KdfParams) -> Long = { p ->
        requested += p
        if (p.memoryKiB > maxAllocKiB) throw allocFailure()
        (msPerIterAt64MiB * p.memoryKiB / KdfParams.DEFAULT_MEMORY_KIB * p.iterations).toLong()
    }

    @Test
    fun typicalDeviceKeepsDefaults() {
        // 64 MiB, t = 3 → 750ms: 500 ~ 1200 사이라 그대로
        assertEquals(KdfParams(65536, 3, 2), calibrateKdf(device(250.0)))
    }

    @Test
    fun fastDeviceRaisesIterationsUntil500ms() {
        // t3 = 360, t4 = 480, t5 = 600 → t = 5 에서 멈춘다
        assertEquals(KdfParams(65536, 5, 2), calibrateKdf(device(120.0)))
    }

    @Test
    fun veryFastDeviceCapsAtMaxIterations() {
        assertEquals(KdfParams(65536, 8, 2), calibrateKdf(device(10.0)))
    }

    @Test
    fun slowDeviceHalvesMemoryToFloorAndAcceptsSlowness() {
        // 64 MiB t3 = 3000 → 32 MiB t3 = 1500: 여전히 느리지만 하한이므로 받아들인다
        assertEquals(KdfParams(32768, 3, 2), calibrateKdf(device(1000.0)))
        assertTrue(requested.all { it.memoryKiB >= KdfParams.MIN_MEMORY_KIB })
    }

    @Test
    fun nativeAllocationFailureFallsBackTo32MiB() {
        val p = calibrateKdf(device(250.0, maxAllocKiB = 32768))
        assertEquals(32768, p.memoryKiB)
    }

    @Test
    fun directBufferOutOfMemoryFallsBackTo32MiB() {
        val p = calibrateKdf(device(250.0, maxAllocKiB = 32768, allocFailure = { OutOfMemoryError() }))
        assertEquals(32768, p.memoryKiB)
    }

    @Test
    fun allocationFailureAtFloorIsRethrown() {
        assertThrows(Argon2Exception::class.java) { calibrateKdf(device(250.0, maxAllocKiB = 16384)) }
    }

    @Test
    fun unrelatedFailureIsNotSwallowedAsAllocationFailure() {
        assertThrows(IllegalStateException::class.java) {
            calibrateKdf(device(250.0, maxAllocKiB = 0, allocFailure = { IllegalStateException("bug") }))
        }
        assertEquals("m 을 낮춰 재시도하지 않는다", 1, requested.size)
    }

    @Test
    fun calibratedParamsAreAlwaysAcceptedByVaultMetaDecoder() {
        // 캘리브레이션 산출 범위와 DM-01 읽기 검사 범위가 어긋나면 새 금고가 즉시 Corrupt 가 된다.
        val devices = listOf(1.0, 10.0, 60.0, 120.0, 250.0, 400.0, 1000.0, 5000.0).map { device(it) } +
            device(250.0, maxAllocKiB = 32768)
        for (d in devices) {
            val params = calibrateKdf(d)
            val meta = VaultMeta(ByteArray(SALT_BYTES), params, ByteArray(60), null, 0, 0, 0)
            val decoded = VaultMetaStore.decode(VaultMetaStore.encode(meta))
            assertEquals("$params", params, decoded?.kdfParams)
        }
    }
}
