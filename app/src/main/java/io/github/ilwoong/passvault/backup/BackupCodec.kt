package io.github.ilwoong.passvault.backup

import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryType
import io.github.ilwoong.passvault.data.model.MAX_TEXT_LENGTH
import io.github.ilwoong.passvault.data.model.MAX_TITLE_LENGTH
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.policy.isEmpty
import io.github.ilwoong.passvault.security.KdfParams
import io.github.ilwoong.passvault.security.toUtf8Bytes
import io.github.ilwoong.passvault.security.useThenZeroize
import io.github.ilwoong.passvault.security.zeroize
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** BK-06 로 구분하는 결과. */
sealed interface BackupResult {
    data class Success(val entries: List<Entry>, val exportedAtEpochMs: Long) : BackupResult {
        override fun toString() = "Success(${entries.size} entries)"
    }

    data object NotBackup : BackupResult
    data object NewerVersion : BackupResult
    data object TooLarge : BackupResult

    /** GCM 태그 실패. 둘을 구분할 방법이 없다 (BK-06). */
    data object WrongPasswordOrCorrupt : BackupResult
    data object Malformed : BackupResult
}

/**
 * BK-01 ~ BK-08: `.pvault` 파일. 헤더 50 바이트 전체가 AAD 다.
 *
 * SEC-12 예외: JSON 직렬화는 String 을 거친다. 평문 바이트 버퍼는 쓰고 나서 지운다.
 * 평문을 임시 파일로 쓰지 않는다 (BK-03).
 */
class BackupCodec(
    private val derive: (password: ByteArray, salt: ByteArray, params: KdfParams) -> ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {

    fun encode(entries: List<Entry>, password: CharArray, appVersion: String, nowEpochMs: Long): ByteArray {
        val plain = toJson(entries, appVersion, nowEpochMs).toString().toByteArray(Charsets.UTF_8)
        val compressed = try {
            gzip(plain)
        } finally {
            plain.zeroize()
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(VERSION).put(KDF_ARGON2ID)
            .putInt(PARAMS.memoryKiB).putInt(PARAMS.iterations).putInt(PARAMS.parallelism)
            .put(salt).put(nonce)
            .array()
        val key = password.toUtf8Bytes().useThenZeroize { pw -> derive(pw, salt, PARAMS) }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(compressed)
        } finally {
            key.zeroize()
            compressed.zeroize()
        }
    }

    /** 비밀번호를 묻기 전에 파일 종류·버전만 확인한다. 문제가 없으면 null. */
    fun inspect(bytes: ByteArray): BackupResult? = when {
        bytes.size > MAX_FILE_BYTES -> BackupResult.TooLarge
        bytes.size < HEADER_BYTES + TAG_BITS / 8 || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC) -> BackupResult.NotBackup
        bytes[MAGIC.size] > VERSION -> BackupResult.NewerVersion
        bytes[MAGIC.size] < 1 || bytes[MAGIC.size + 1] != KDF_ARGON2ID -> BackupResult.Malformed
        else -> null
    }

    fun decode(bytes: ByteArray, password: CharArray): BackupResult {
        inspect(bytes)?.let { return it }
        val buf = ByteBuffer.wrap(bytes, MAGIC.size + 2, HEADER_BYTES - MAGIC.size - 2)
        val params = KdfParams(buf.int, buf.int, buf.int)
        // 파일이 정한 파라미터로 메모리를 잡는다 — 터무니없는 값으로 기기를 멈추게 하지 못하게 상한을 둔다
        if (params.memoryKiB !in 8..MAX_MEMORY_KIB || params.iterations !in 1..64 || params.parallelism !in 1..16) {
            return BackupResult.Malformed
        }
        val salt = ByteArray(SALT_BYTES).also(buf::get)
        val nonce = ByteArray(NONCE_BYTES).also(buf::get)

        val key = password.toUtf8Bytes().useThenZeroize { pw -> derive(pw, salt, params) }
        val compressed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(bytes, 0, HEADER_BYTES)
            cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            return BackupResult.WrongPasswordOrCorrupt
        } finally {
            key.zeroize()
        }
        val plain = try {
            gunzip(compressed) ?: return BackupResult.Malformed
        } finally {
            compressed.zeroize()
        }
        return try {
            parse(String(plain, Charsets.UTF_8))
        } catch (e: JSONException) {
            BackupResult.Malformed
        } catch (e: InvalidBackup) {
            BackupResult.Malformed
        } finally {
            plain.zeroize()
        }
    }

    // --- BK-02 ---

    private fun toJson(entries: List<Entry>, appVersion: String, now: Long) = JSONObject()
        .put("formatVersion", VERSION.toInt())
        .put("exportedAtEpochMs", now)
        .put("appVersion", appVersion)
        .put("entries", JSONArray(entries.map(::entryJson)))

    private fun entryJson(e: Entry): JSONObject {
        val o = JSONObject()
            .put("id", e.id)
            .put("type", e.content.type.name)
            .put("title", e.title)
            .put("isFavorite", e.isFavorite)
            .put("createdAtEpochMs", e.createdAtEpochMs)
            .put("updatedAtEpochMs", e.updatedAtEpochMs)
        // 캐시(subtitle, last4, hasPolicyViolation, hasRotationDue)는 내보내지 않는다 — 복구 시 다시 계산한다
        when (val c = e.content) {
            is EntryContent.Login -> o.put(
                "login",
                JSONObject()
                    .putOpt("username", c.username).putOpt("password", c.password)
                    .putOpt("url", c.url).putOpt("memo", c.memo)
                    .putOpt("passwordUpdatedAtEpochMs", e.passwordUpdatedAtEpochMs)
                    .putOpt("policy", c.policy?.let(::policyJson)),
            )
            is EntryContent.Note -> o.put("note", JSONObject().put("body", c.body))
            is EntryContent.Card -> o.put(
                "card",
                JSONObject()
                    .putOpt("cardholderName", c.cardholderName).putOpt("number", c.number).putOpt("brand", c.brand)
                    .putOpt("expiryMonth", c.expiryMonth).putOpt("expiryYear", c.expiryYear)
                    .putOpt("cvc", c.cvc).putOpt("pin", c.pin).putOpt("memo", c.memo),
            )
            is EntryContent.Identity -> o.put(
                "identity",
                JSONObject()
                    .putOpt("docType", c.docType).putOpt("fullName", c.fullName).putOpt("docNumber", c.docNumber)
                    .putOpt("issuer", c.issuer).putOpt("issuedDate", c.issuedDate).putOpt("expiryDate", c.expiryDate)
                    .putOpt("memo", c.memo),
            )
        }
        return o
    }

    private fun policyJson(p: PasswordPolicy) = JSONObject()
        .putOpt("minLength", p.minLength).putOpt("maxLength", p.maxLength)
        .put("upperRule", p.upperRule.name).put("lowerRule", p.lowerRule.name)
        .put("digitRule", p.digitRule.name).put("symbolRule", p.symbolRule.name)
        .putOpt("allowedSymbols", p.allowedSymbols).putOpt("forbiddenSymbols", p.forbiddenSymbols)
        .putOpt("maxRepeatRun", p.maxRepeatRun).put("disallowSpace", p.disallowSpace)
        .putOpt("rotationDays", p.rotationDays).putOpt("rawNote", p.rawNote)

    // --- BK-08 검증 ---

    private class InvalidBackup(reason: String) : Exception(reason)

    private fun parse(text: String): BackupResult {
        val root = JSONObject(text)
        val exportedAt = root.optLong("exportedAtEpochMs", 0)
        val array = root.optJSONArray("entries") ?: throw InvalidBackup("entries 없음")
        if (array.length() > MAX_ENTRIES) throw InvalidBackup("항목 수 초과")
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val entries = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val id = o.getString("id")
            if (!isUuid(id) || !seen.add(id)) throw InvalidBackup("id")
            // 알 수 없는 타입은 그 항목만 건너뛰지 않고 파일 전체를 거부한다 — 조용한 데이터 손실 방지
            val type = EntryType.entries.firstOrNull { it.name == o.optString("type") } ?: throw InvalidBackup("type")
            val title = limited(o.optString("title"), MAX_TITLE).ifBlank { UNTITLED }
            val content = when (type) {
                EntryType.LOGIN -> o.getJSONObject("login").let {
                    EntryContent.Login(str(it, "username"), str(it, "password"), str(it, "url"), str(it, "memo"), policy(it.optJSONObject("policy")))
                }
                EntryType.NOTE -> EntryContent.Note(str(o.getJSONObject("note"), "body").orEmpty())
                EntryType.CARD -> o.getJSONObject("card").let {
                    EntryContent.Card(
                        str(it, "cardholderName"), str(it, "number"), str(it, "brand"),
                        int(it, "expiryMonth")?.takeIf { m -> m in 1..12 }, int(it, "expiryYear")?.takeIf { y -> y in 1000..9999 },
                        str(it, "cvc"), str(it, "pin"), str(it, "memo"),
                    )
                }
                EntryType.IDENTITY -> o.getJSONObject("identity").let {
                    EntryContent.Identity(
                        str(it, "docType"), str(it, "fullName"), str(it, "docNumber"), str(it, "issuer"),
                        date(str(it, "issuedDate")), date(str(it, "expiryDate")), str(it, "memo"),
                    )
                }
            }
            Entry(
                id = id,
                title = title,
                isFavorite = o.optBoolean("isFavorite", false),
                createdAtEpochMs = long(o, "createdAtEpochMs") ?: now,
                updatedAtEpochMs = long(o, "updatedAtEpochMs") ?: now,
                passwordUpdatedAtEpochMs = if (type == EntryType.LOGIN) long(o.getJSONObject("login"), "passwordUpdatedAtEpochMs") else null,
                content = content,
            )
        }
        return BackupResult.Success(entries, exportedAt)
    }

    private fun policy(o: JSONObject?): PasswordPolicy? {
        o ?: return null
        fun rule(k: String) = CharClassRule.entries.firstOrNull { it.name == o.optString(k) } ?: CharClassRule.UNKNOWN
        return PasswordPolicy(
            minLength = int(o, "minLength")?.takeIf { it >= 1 },
            maxLength = int(o, "maxLength")?.takeIf { it >= 1 },
            upperRule = rule("upperRule"),
            lowerRule = rule("lowerRule"),
            digitRule = rule("digitRule"),
            symbolRule = rule("symbolRule"),
            allowedSymbols = str(o, "allowedSymbols"),
            forbiddenSymbols = str(o, "forbiddenSymbols"),
            maxRepeatRun = int(o, "maxRepeatRun")?.takeIf { it >= 1 },
            disallowSpace = o.optBoolean("disallowSpace", false),
            rotationDays = int(o, "rotationDays")?.takeIf { it >= 1 },
            rawNote = str(o, "rawNote"),
        ).takeUnless { it.isEmpty }
    }

    /** 문자열이 아니거나 비었으면 null. 상한을 넘으면 파일 전체를 거부한다. */
    private fun str(o: JSONObject, k: String): String? =
        (o.opt(k) as? String)?.let { limited(it, MAX_TEXT) }?.takeIf { it.isNotEmpty() }

    private fun limited(s: String, max: Int): String = if (s.length > max) throw InvalidBackup("길이 초과") else s

    private fun int(o: JSONObject, k: String): Int? =
        (o.opt(k) as? Number)?.toLong()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    /** 범위를 벗어나면 null (BK-08). */
    private fun long(o: JSONObject, k: String): Long? = (o.opt(k) as? Number)?.toLong()?.takeIf { it >= 0 }

    private fun date(s: String?): String? = s?.let {
        try {
            LocalDate.parse(it).toString()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun isUuid(s: String) = try {
        UUID.fromString(s).toString() == s.lowercase()
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** 압축 폭탄을 막으려고 풀린 크기에 상한을 둔다. 넘으면 null. */
    private fun gunzip(data: ByteArray): ByteArray? {
        val chunk = ByteArray(64 * 1024)
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_PLAIN_BYTES) return null
                }
                return out.toByteArray()
            }
        } catch (e: java.io.IOException) {
            return null
        } finally {
            chunk.zeroize()
        }
    }

    companion object {
        val MAGIC = byteArrayOf(0x50, 0x56, 0x41, 0x55, 0x4C, 0x54, 0x00, 0x00) // "PVAULT\0\0"
        const val VERSION: Byte = 1
        const val KDF_ARGON2ID: Byte = 1
        const val HEADER_BYTES = 50

        /** BK-01: 금고 캘리브레이션 값이 아니라 고정값. 오프라인 대입에 노출되는 파일이라 더 강하게. */
        val PARAMS = KdfParams(memoryKiB = 64 * 1024, iterations = 4, parallelism = 2)

        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        const val MAX_ENTRIES = 50_000
        const val MAX_TITLE = MAX_TITLE_LENGTH
        const val MAX_TEXT = MAX_TEXT_LENGTH
        const val UNTITLED = "(제목 없음)"

        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_MEMORY_KIB = 256 * 1024
        /**
         * 풀린 평문 상한. 정당한 금고(5만 건 × 1KB 안팎)는 넉넉히 담고, 버퍼가 두 배로 자라도
         * 기기 힙을 넘지 않을 크기다. 256 MiB 로 두면 상한 검사 전에 OOM 으로 죽는다 (M8 테스트에서 발견).
         */
        private const val MAX_PLAIN_BYTES = 64 * 1024 * 1024
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
