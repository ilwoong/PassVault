package io.github.ilwoong.passvault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.db.VaultDatabase
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.repo.EntryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/** TST-05 DB 암호화, TST-06 외래키·CASCADE, CRY-17 패스프레이즈 수명 */
@RunWith(AndroidJUnit4::class)
class VaultDatabaseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-${System.nanoTime()}.db"
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    // --- TST-05 ---

    @Test
    fun databaseFilesContainNoPlaintextAndNoSqliteHeader() = runBlocking {
        val marker = "PlaintextMarker-비밀-12345"
        val db = VaultDatabase.open(context, key, name)
        val repo = EntryRepository(db.dao())
        repeat(20) {
            repo.save(EntryDraft(null, "title-$marker", false, EntryContent.Login(username = marker, password = marker, memo = marker)))
        }

        // 열린 상태: 최근 쓰기는 WAL 파일에 있다
        assertNoPlaintext(marker)
        db.close()
        assertNoPlaintext(marker)
    }

    @Test
    fun dataSurvivesReopenWithSameKey() = runBlocking {
        val db = VaultDatabase.open(context, key, name)
        val id = EntryRepository(db.dao()).save(EntryDraft(null, "t", false, EntryContent.Note("body")))
        db.close()

        val reopened = VaultDatabase.open(context, key.copyOf(), name)
        assertEquals(EntryContent.Note("body"), EntryRepository(reopened.dao()).get(id)?.content)
        reopened.close()
    }

    @Test
    fun wrongKeyCannotReadDatabase() = runBlocking {
        val db = VaultDatabase.open(context, key, name)
        EntryRepository(db.dao()).save(EntryDraft(null, "t", false, EntryContent.Note("body")))
        db.close()

        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrong = VaultDatabase.open(context, wrongKey, name)
        val result = runCatching { wrong.dao().observeSummaries(null, null).first() }
        wrong.close()

        assertTrue("다른 키로 읽기가 성공하면 안 된다: $result", result.isFailure)
    }

    // --- TST-06 ---

    @Test
    fun foreignKeysAreEnabled() {
        val db = VaultDatabase.open(context, key, name)
        db.openHelper.readableDatabase.query("PRAGMA foreign_keys").use {
            assertTrue(it.moveToFirst())
            assertEquals("DM-11: CASCADE 는 외래키가 켜져 있어야 동작한다", 1, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun deletingEntryCascadesToDetailAndPolicy() = runBlocking {
        val db = VaultDatabase.open(context, key, name)
        val repo = EntryRepository(db.dao())
        val id = repo.save(
            EntryDraft(null, "t", false, EntryContent.Login(password = "pw", policy = PasswordPolicy(minLength = 8))),
        )
        assertEquals(1, count(db, "login_detail"))
        assertEquals(1, count(db, "password_policy"))

        repo.delete(id)

        assertEquals(0, count(db, "entry"))
        assertEquals(0, count(db, "login_detail"))
        assertEquals(0, count(db, "password_policy"))
        db.close()
    }

    // --- CRY-17 ---

    @Test
    fun passphraseStaysValidWhileOpenAndIsZeroizedOnlyAfterClose() = runBlocking {
        val db = VaultDatabase.open(context, key, name)
        val repo = EntryRepository(db.dao())
        repeat(5) { repo.save(EntryDraft(null, "t$it", false, EntryContent.Note("b$it"))) }

        // 병렬 읽기로 WAL 커넥션 풀이 추가 커넥션을 열게 한다. 각 커넥션은 패스프레이즈로 키를 건다.
        val results = (1..16).map { async(Dispatchers.IO) { repo.observeSummaries().first().size } }.awaitAll()
        assertTrue(results.all { it == 5 })

        val passphrase = checkNotNull(db.passphrase)
        assertFalse("열려 있는 동안 지우면 이후 커넥션이 실패한다", passphrase.all { it == 0.toByte() })

        db.close()

        assertTrue("close 후에는 지워져야 한다", passphrase.all { it == 0.toByte() })
    }

    private fun dbFiles(): List<File> {
        val main = context.getDatabasePath(name)
        return main.parentFile!!.listFiles { f -> f.name.startsWith(name) }!!.toList()
    }

    private fun assertNoPlaintext(marker: String) {
        val files = dbFiles()
        assertTrue("DB 파일이 있어야 한다: $files", files.any { it.name == name })
        val needles = listOf("SQLite format 3".toByteArray(), marker.toByteArray(Charsets.UTF_8))
        for (f in files) {
            val bytes = f.readBytes()
            for (n in needles) {
                assertFalse("${f.name} 에 평문 '${String(n)}' 이 있다", bytes.containsSequence(n))
            }
        }
    }

    private fun count(db: VaultDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
}

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}
