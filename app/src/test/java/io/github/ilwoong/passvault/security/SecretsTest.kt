package io.github.ilwoong.passvault.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** TST-04 */
class SecretsTest {

    @Test
    fun zeroizeByteArray() {
        val a = byteArrayOf(1, 2, 3)
        a.zeroize()
        assertArrayEquals(ByteArray(3), a)
    }

    @Test
    fun zeroizeCharArray() {
        val a = charArrayOf('a', 'b')
        a.zeroize()
        assertArrayEquals(CharArray(2), a)
    }

    @Test
    fun zeroizeDirectBufferCoversFullCapacityRegardlessOfPositionAndLimit() {
        val b = ByteBuffer.allocateDirect(32)
        repeat(32) { b.put(0x5A) }
        b.position(10).limit(20)

        b.zeroize()

        b.clear()
        repeat(32) { assertEquals("index $it", 0.toByte(), b.get()) }
    }

    @Test
    fun useThenZeroizeClearsOnNormalReturn() {
        val a = byteArrayOf(9, 9)
        val r = a.useThenZeroize { it.sum() }
        assertEquals(18, r)
        assertArrayEquals(ByteArray(2), a)
    }

    @Test
    fun useThenZeroizeClearsWhenBlockThrows() {
        val bytes = byteArrayOf(9, 9)
        val chars = charArrayOf('x')
        assertThrows(IllegalStateException::class.java) { bytes.useThenZeroize { error("boom") } }
        assertThrows(IllegalStateException::class.java) { chars.useThenZeroize { error("boom") } }
        assertArrayEquals(ByteArray(2), bytes)
        assertArrayEquals(CharArray(1), chars)
    }

    @Test
    fun toUtf8BytesMatchesStandardEncoding() {
        // 1·3·4 바이트 문자와 빈 입력. 4 바이트는 서로게이트 쌍(2 char)이다.
        for (s in listOf("", "password", "한글비밀번호", "🔐key🔑", "mixed 한 🔐")) {
            assertArrayEquals(s, s.toByteArray(Charsets.UTF_8), s.toCharArray().toUtf8Bytes())
        }
    }

    @Test
    fun toUtf8BytesRejectsLoneSurrogate() {
        assertThrows(CharacterCodingException::class.java) { charArrayOf('a', '\uD83D').toUtf8Bytes() }
    }
}
