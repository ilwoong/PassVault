package io.github.ilwoong.passvault.security

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Argon2KeyDeriverTest {

    private val deriver = Argon2KeyDeriver()

    // --- CRY-02 알려진 답 테스트 ---
    // phc-winner-argon2 src/test.c 의 Argon2id v0x13 벡터. password = "password", salt = "somesalt".
    // 모드(id/i/d)나 t·m 인자 순서가 어긋나면 여기서 깨진다.

    @Test
    fun knownAnswer_t2_m64MiB_p1() = assertKnownAnswer(
        KdfParams(memoryKiB = 65536, iterations = 2, parallelism = 1),
        "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7",
    )

    @Test
    fun knownAnswer_t2_m256KiB_p2() = assertKnownAnswer(
        KdfParams(memoryKiB = 256, iterations = 2, parallelism = 2),
        "6d093c501fd5999645e0ea3bf620d7b8be7fd2db59c20d9fff9539da2bf57037",
    )

    @Test
    fun knownAnswer_t1_m64MiB_p1() = assertKnownAnswer(
        KdfParams(memoryKiB = 65536, iterations = 1, parallelism = 1),
        "f6a5adc1ba723dddef9b5ac1d464e180fcd9dffc9d1cbf76cca2fed795d9ca98",
    )

    @Test
    fun outputIsDeterministicAndSensitiveToEveryInput() {
        val p = KdfParams(256, 2, 2)
        val pw = "pw".toByteArray()
        val salt = ByteArray(SALT_BYTES) { 1 }
        val base = deriver.derive(pw, salt, p)

        assertEquals(KEY_BYTES, base.size)
        assertArrayEquals(base, deriver.derive(pw, salt, p))
        assertFalse(base.contentEquals(deriver.derive("px".toByteArray(), salt, p)))
        assertFalse(base.contentEquals(deriver.derive(pw, ByteArray(SALT_BYTES) { 2 }, p)))
        assertFalse(base.contentEquals(deriver.derive(pw, salt, p.copy(iterations = 3))))
    }

    // --- CRY-16 ---

    @Test
    fun resultBuffersHoldingMasterKeyAreWritableAndFullyZeroized() {
        // derive() 의 finally 가 하는 일을 실제 네이티브 결과 버퍼에 대해 확인한다.
        // 읽기 전용 버퍼였다면 zeroize() 가 ReadOnlyBufferException 을 던진다.
        val r = Argon2Kt().hash(Argon2Mode.ARGON2_ID, "pw".toByteArray(), ByteArray(SALT_BYTES), 1, 256, 1, KEY_BYTES)
        r.rawHashAsByteArray() // derive() 와 같은 순서로 먼저 꺼낸다 (position 변경 가능)

        r.rawHash.zeroize()
        r.encodedOutput.zeroize()

        for (buf in listOf(r.rawHash, r.encodedOutput)) {
            buf.clear()
            while (buf.hasRemaining()) assertEquals(0.toByte(), buf.get())
        }
    }

    // --- TST-10 / NFR-01 ---

    @Test
    fun calibratedParamsMeetUnlockBudget() {
        val start = System.nanoTime()
        val params = deriver.calibrate()
        val calibrationMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("$params", params.memoryKiB in KdfParams.MIN_MEMORY_KIB..KdfParams.DEFAULT_MEMORY_KIB)
        assertTrue("$params", params.iterations in KdfParams.DEFAULT_ITERATIONS..KdfParams.MAX_ITERATIONS)
        assertEquals(KdfParams.PARALLELISM, params.parallelism)

        val t0 = System.nanoTime()
        deriver.derive("correct horse".toByteArray(), ByteArray(SALT_BYTES), params).zeroize()
        val unlockMs = (System.nanoTime() - t0) / 1_000_000

        Log.i("TST-10", "calibrated=$params calibration=${calibrationMs}ms derive=${unlockMs}ms")
        assertTrue("NFR-01: derive ${unlockMs}ms > 1500ms at $params", unlockMs <= 1500)
    }

    private fun assertKnownAnswer(params: KdfParams, expectedHex: String) {
        val actual = deriver.derive("password".toByteArray(), "somesalt".toByteArray(), params)
        assertEquals(expectedHex, actual.joinToString("") { "%02x".format(it) })
    }
}
