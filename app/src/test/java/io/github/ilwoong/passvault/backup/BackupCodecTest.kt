package io.github.ilwoong.passvault.backup

import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.security.KdfParams
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** BK-01·02·06·07·08. Argon2 대신 결정적인 가짜 KDF 로 포맷·검증 규칙만 본다. */
class BackupCodecTest {

    private var deriveCalls = 0
    private val fakeDerive: (ByteArray, ByteArray, KdfParams) -> ByteArray = { pw, salt, p ->
        deriveCalls++
        MessageDigest.getInstance("SHA-256").digest(pw + salt + "${p.memoryKiB}/${p.iterations}/${p.parallelism}".toByteArray())
    }
    private val codec = BackupCodec(fakeDerive)
    private val pw = "backup-password-123"

    private val samples = listOf(
        Entry(
            UUID.randomUUID().toString(), "GitHub", true, 1_000, 2_000, 1_500,
            EntryContent.Login(
                "octocat", "S3cret!", "https://github.com", "memo",
                PasswordPolicy(
                    minLength = 10, maxLength = 20, upperRule = CharClassRule.REQUIRED, symbolRule = CharClassRule.FORBIDDEN,
                    allowedSymbols = "!@#", maxRepeatRun = 2, disallowSpace = true, rotationDays = 90, rawNote = "원문 규칙",
                ),
            ),
        ),
        Entry(UUID.randomUUID().toString(), "메모", false, 3_000, 4_000, null, EntryContent.Note("본문 🔐")),
        Entry(
            UUID.randomUUID().toString(), "카드", false, 5_000, 6_000, null,
            EntryContent.Card("홍길동", "4111111111111111", "VISA", 12, 2030, "123", "0000", "m"),
        ),
        Entry(
            UUID.randomUUID().toString(), "여권", false, 7_000, 8_000, null,
            EntryContent.Identity("여권", "홍길동", "M12345678", "외교부", "2020-01-01", "2030-01-01", null),
        ),
    )

    private fun encode(entries: List<Entry> = samples) = codec.encode(entries, pw.toCharArray(), "1.0.0", 9_999)

    // --- 왕복 ---

    @Test
    fun roundTripKeepsEveryField() {
        val r = codec.decode(encode(), pw.toCharArray()) as BackupResult.Success
        assertEquals(samples, r.entries)
        assertEquals(9_999L, r.exportedAtEpochMs)
    }

    @Test
    fun emptyVaultBackupIsValid() {
        assertEquals(emptyList<Entry>(), (codec.decode(encode(emptyList()), pw.toCharArray()) as BackupResult.Success).entries)
    }

    @Test
    fun payloadCarriesNoCachesKeysOrSettings() {
        val json = decryptToJson(encode())
        for (forbidden in listOf("subtitle", "last4", "hasPolicyViolation", "hasRotationDue", "wrapped", "kdf", "autoLock", "salt")) {
            assertFalse("payload 에 $forbidden 이 있으면 안 된다", json.contains(forbidden))
        }
        assertEquals(1, JSONObject(json).getInt("formatVersion"))
    }

    @Test
    fun headerLayoutMatchesSpec() {
        val bytes = encode()
        assertEquals("PVAULT", String(bytes.copyOf(6), Charsets.US_ASCII))
        assertEquals(0, bytes[6].toInt()); assertEquals(0, bytes[7].toInt())
        assertEquals(1, bytes[8].toInt()); assertEquals(1, bytes[9].toInt())
        val b = ByteBuffer.wrap(bytes, 10, 12)
        assertEquals(BackupCodec.PARAMS, KdfParams(b.int, b.int, b.int))
    }

    @Test
    fun eachEncodingUsesFreshSaltAndNonce() {
        val a = encode(); val b = encode()
        assertFalse(a.copyOfRange(22, 50).contentEquals(b.copyOfRange(22, 50)))
    }

    // --- BK-06 ---

    @Test
    fun wrongPassword() = assertEquals(BackupResult.WrongPasswordOrCorrupt, codec.decode(encode(), "nope".toCharArray()))

    @Test
    fun tamperedHeaderOrCiphertextIsDetected() {
        val good = encode()
        for (i in listOf(22, 40, 49, 60, good.size - 1)) { // salt, nonce, 암호문, 태그
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertEquals("byte $i", BackupResult.WrongPasswordOrCorrupt, codec.decode(bad, pw.toCharArray()))
        }
        // KDF 파라미터를 범위 안의 다른 값으로 → AAD 불일치
        val bad = good.copyOf().also { ByteBuffer.wrap(it).putInt(14, 3) }
        assertEquals(BackupResult.WrongPasswordOrCorrupt, codec.decode(bad, pw.toCharArray()))
    }

    @Test
    fun notABackup() {
        assertEquals(BackupResult.NotBackup, codec.decode(ByteArray(100), pw.toCharArray()))
        assertEquals(BackupResult.NotBackup, codec.decode(encode().copyOf(40), pw.toCharArray()))
    }

    @Test
    fun newerFormatIsRejectedBeforeAskingTheKdf() {
        val bytes = encode().also { it[8] = 2 }
        deriveCalls = 0
        assertEquals(BackupResult.NewerVersion, codec.decode(bytes, pw.toCharArray()))
        assertEquals(BackupResult.NewerVersion, codec.inspect(bytes))
        assertEquals(0, deriveCalls)
    }

    @Test
    fun absurdKdfParamsAreRejectedWithoutRunningKdf() {
        val bytes = encode().also { ByteBuffer.wrap(it).putInt(10, 4 * 1024 * 1024) } // 4 GiB
        deriveCalls = 0
        assertEquals(BackupResult.Malformed, codec.decode(bytes, pw.toCharArray()))
        assertEquals(0, deriveCalls)
    }

    @Test
    fun tooLarge() = assertEquals(BackupResult.TooLarge, codec.inspect(ByteArray(BackupCodec.MAX_FILE_BYTES + 1)))

    // --- BK-08 (복호화는 되지만 내용이 잘못된 파일) ---

    @Test
    fun nonJsonIsMalformed() = assertEquals(BackupResult.Malformed, decodeRaw("not json".toByteArray()))

    @Test
    fun gzipBombIsMalformed() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { z -> val zeros = ByteArray(1 shl 20); repeat(65) { z.write(zeros) } } // 65 MiB 로 풀린다
        assertEquals(BackupResult.Malformed, decodeRaw(out.toByteArray(), gzipped = true))
    }

    @Test
    fun duplicateOrInvalidIdsRejectTheWholeFile() {
        val id = UUID.randomUUID().toString()
        assertEquals(BackupResult.Malformed, decodeJson(listOf(note(id), note(id))))
        assertEquals(BackupResult.Malformed, decodeJson(listOf(note("not-a-uuid"))))
    }

    @Test
    fun unknownTypeRejectsTheWholeFile() {
        val e = note(UUID.randomUUID().toString()).put("type", "WALLET")
        assertEquals("조용한 데이터 손실 대신 거부", BackupResult.Malformed, decodeJson(listOf(e)))
    }

    @Test
    fun lengthAndCountLimits() {
        assertEquals(BackupResult.Malformed, decodeJson(listOf(note(UUID.randomUUID().toString()).put("title", "x".repeat(201)))))
        val longBody = note(UUID.randomUUID().toString()).put("note", JSONObject().put("body", "x".repeat(20_001)))
        assertEquals(BackupResult.Malformed, decodeJson(listOf(longBody)))
        assertEquals(BackupResult.Malformed, decodeJson((0..50_000).map { note(UUID.randomUUID().toString()) }))
    }

    @Test
    fun blankTitleAndOutOfRangeValuesAreRepairedNotRejected() {
        val id = UUID.randomUUID().toString()
        val card = JSONObject().put("id", id).put("type", "CARD").put("title", "  ")
            .put("createdAtEpochMs", -5).put("updatedAtEpochMs", 10)
            .put("card", JSONObject().put("expiryMonth", 13).put("expiryYear", 99).put("number", "4111"))
        val identity = JSONObject().put("id", UUID.randomUUID().toString()).put("type", "IDENTITY").put("title", "t")
            .put("identity", JSONObject().put("issuedDate", "2023-02-30").put("expiryDate", "2030-01-01"))
        val login = JSONObject().put("id", UUID.randomUUID().toString()).put("type", "LOGIN").put("title", "t")
            .put("unknownFutureField", "ignored")
            .put("login", JSONObject().put("password", "p").put("passwordUpdatedAtEpochMs", -1)
                .put("policy", JSONObject().put("upperRule", "SOMETIMES").put("minLength", 0).put("rawNote", "r")))
        val r = decodeJson(listOf(card, identity, login)) as BackupResult.Success

        val c = r.entries[0]
        assertEquals(BackupCodec.UNTITLED, c.title)
        assertTrue("음수 시각은 지금으로", c.createdAtEpochMs > 1_000_000)
        assertNull((c.content as EntryContent.Card).expiryMonth)
        assertNull((c.content as EntryContent.Card).expiryYear)
        assertNull((r.entries[1].content as EntryContent.Identity).issuedDate)
        assertEquals("2030-01-01", (r.entries[1].content as EntryContent.Identity).expiryDate)
        val l = r.entries[2]
        assertNull(l.passwordUpdatedAtEpochMs)
        val policy = (l.content as EntryContent.Login).policy!!
        assertEquals(CharClassRule.UNKNOWN, policy.upperRule)
        assertNull(policy.minLength)
        assertEquals("r", policy.rawNote)
    }

    // --- 도우미: 포맷 그대로 임의 평문을 암호화한다 ---

    private fun note(id: String) = JSONObject().put("id", id).put("type", "NOTE").put("title", "t")
        .put("note", JSONObject().put("body", "b"))

    private fun decodeJson(entries: List<JSONObject>): BackupResult =
        decodeRaw(JSONObject().put("formatVersion", 1).put("entries", JSONArray(entries)).toString().toByteArray())

    private fun decodeRaw(payload: ByteArray, gzipped: Boolean = false): BackupResult {
        val compressed = if (gzipped) payload else ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(payload) } }.toByteArray()
        val salt = ByteArray(16) { 7 }
        val nonce = ByteArray(12) { 9 }
        val header = ByteBuffer.allocate(50).put(BackupCodec.MAGIC).put(1).put(1)
            .putInt(BackupCodec.PARAMS.memoryKiB).putInt(BackupCodec.PARAMS.iterations).putInt(BackupCodec.PARAMS.parallelism)
            .put(salt).put(nonce).array()
        val key = fakeDerive(pw.toByteArray(), salt, BackupCodec.PARAMS)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(header)
        }
        return codec.decode(header + c.doFinal(compressed), pw.toCharArray())
    }

    private fun decryptToJson(bytes: ByteArray): String {
        val b = ByteBuffer.wrap(bytes, 10, 40)
        val params = KdfParams(b.int, b.int, b.int)
        val salt = ByteArray(16).also(b::get)
        val nonce = ByteArray(12).also(b::get)
        val key = fakeDerive(pw.toByteArray(), salt, params)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(bytes, 0, 50)
        }
        val gz = c.doFinal(bytes, 50, bytes.size - 50)
        return GZIPInputStream(ByteArrayInputStream(gz)).readBytes().toString(Charsets.UTF_8)
    }
}
