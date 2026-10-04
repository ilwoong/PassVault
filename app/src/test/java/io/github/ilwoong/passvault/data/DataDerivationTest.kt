package io.github.ilwoong.passvault.data

import io.github.ilwoong.passvault.data.db.CardDetailEntity
import io.github.ilwoong.passvault.data.db.IdentityDetailEntity
import io.github.ilwoong.passvault.data.db.LoginDetailEntity
import io.github.ilwoong.passvault.data.db.NoteDetailEntity
import io.github.ilwoong.passvault.data.db.rawKeyPassphrase
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.repo.cardLast4
import io.github.ilwoong.passvault.data.repo.normalizeCardNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DataDerivationTest {

    // --- DM-06 카드 번호 ---

    @Test
    fun cardNumberKeepsDigitsOnly() {
        assertEquals("4111111111111111", normalizeCardNumber("4111-1111 1111-1111"))
        assertNull(normalizeCardNumber("--  --"))
        assertNull(normalizeCardNumber(null))
    }

    @Test
    fun last4OnlyWhenItDoesNotRevealTheWholeNumber() {
        assertEquals("1111", cardLast4("4111111111111111"))
        assertEquals("2345", cardLast4("12345"))
        assertNull("4자리면 번호 전체가 드러난다", cardLast4("1234"))
        assertNull(cardLast4("12"))
        assertNull(cardLast4(null))
    }

    // --- CRY-05 raw key ---

    @Test
    fun rawKeyPassphraseIsSqlcipherHexLiteral() {
        val key = ByteArray(32) { it.toByte() }
        val expected = "x'" + key.joinToString("") { "%02x".format(it) } + "'"
        assertEquals(expected, String(rawKeyPassphrase(key), Charsets.US_ASCII))
        assertEquals(67, rawKeyPassphrase(key).size)
    }

    @Test
    fun rawKeyPassphraseHandlesHighBytes() {
        val key = byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00, 0x7F)
        assertEquals("x'ff80007f'", String(rawKeyPassphrase(key), Charsets.US_ASCII))
    }

    // --- SEC-10 / TST-12: 비밀을 담는 타입의 toString ---

    @Test
    fun secretHoldingTypesDoNotRevealContentInToString() {
        val secret = "S3cr3t-Marker"
        val values: List<Any> = listOf(
            EntryContent.Login(username = secret, password = secret, url = secret, memo = secret),
            EntryContent.Note(body = secret),
            EntryContent.Card(number = secret, cvc = secret, pin = secret, memo = secret),
            EntryContent.Identity(docNumber = secret, memo = secret),
            Entry("id", "title", false, 0, 0, null, EntryContent.Login(password = secret)),
            LoginDetailEntity("id", secret, secret, secret, secret, null),
            NoteDetailEntity("id", secret),
            CardDetailEntity("id", secret, secret, secret, secret, 1, 2030, secret, secret, secret),
            IdentityDetailEntity("id", secret, secret, secret, secret, secret, secret, secret),
        )
        for (v in values) {
            assertFalse("${v::class.simpleName}: $v", v.toString().contains(secret))
        }
    }
}
