package io.github.ilwoong.passvault.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** TST-01 (래핑 계층) */
class AesGcmKeyWrapperTest {

    private val wrapper = AesGcmKeyWrapper()
    private val key = ByteArray(KEY_BYTES) { it.toByte() }
    private val vaultKey = ByteArray(KEY_BYTES) { (it * 7 + 1).toByte() }
    private val aad = "aad".toByteArray()

    @Test
    fun roundTrip() {
        val blob = wrapper.wrap(key, vaultKey, aad)
        assertArrayEquals(vaultKey, wrapper.unwrap(key, blob, aad))
    }

    @Test
    fun layoutIsNonceCiphertextTag() {
        // DM-01 의 60 바이트 고정 필드와 일치해야 한다.
        assertEquals(12 + 32 + 16, wrapper.wrap(key, vaultKey, aad).size)
    }

    @Test
    fun nonceIsFreshOnEveryWrap() {
        val a = wrapper.wrap(key, vaultKey, aad)
        val b = wrapper.wrap(key, vaultKey, aad)
        assertFalse(a.copyOf(12).contentEquals(b.copyOf(12)))
    }

    @Test
    fun wrongKeyFailsAsNullNotException() {
        val blob = wrapper.wrap(key, vaultKey, aad)
        assertNull(wrapper.unwrap(ByteArray(KEY_BYTES), blob, aad))
    }

    @Test
    fun differentAadFails() {
        val blob = wrapper.wrap(key, vaultKey, aad)
        assertNull(wrapper.unwrap(key, blob, "other".toByteArray()))
    }

    @Test
    fun everySingleBitFlipIsDetected() {
        val blob = wrapper.wrap(key, vaultKey, aad)
        for (byteIndex in blob.indices) {
            for (bit in 0 until 8) {
                val tampered = blob.copyOf()
                tampered[byteIndex] = (tampered[byteIndex].toInt() xor (1 shl bit)).toByte()
                assertNull("byte $byteIndex bit $bit", wrapper.unwrap(key, tampered, aad))
            }
        }
    }
}
