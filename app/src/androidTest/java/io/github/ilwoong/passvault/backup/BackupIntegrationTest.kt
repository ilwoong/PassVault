package io.github.ilwoong.passvault.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.db.VaultDatabase
import io.github.ilwoong.passvault.data.db.VaultDatabaseHolder
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.AesGcmKeyWrapper
import io.github.ilwoong.passvault.security.Argon2KeyDeriver
import io.github.ilwoong.passvault.security.FakeClocks
import io.github.ilwoong.passvault.security.MetaReadResult
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.SessionState
import io.github.ilwoong.passvault.security.UnlockOutcome
import io.github.ilwoong.passvault.security.VaultKeyManager
import io.github.ilwoong.passvault.security.VaultMetaStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/** TST-09, BK-05, BK-07 — 실제 Argon2id·SQLCipher 위에서 */
@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val codec = BackupCodec(Argon2KeyDeriver()::derive)
    private val dbName = "bk-${System.nanoTime()}.db"
    private val otherDb = "bk2-${System.nanoTime()}.db"
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "bk-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        context.deleteDatabase(dbName)
        context.deleteDatabase(otherDb)
    }

    private fun openDb(name: String) = VaultDatabase.open(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, name)

    // --- TST-09 ---

    @Test
    fun exportWipeImportGivesIdenticalEntriesAndRecomputedCaches() = runBlocking {
        val source = openDb(dbName)
        val repo = EntryRepository(source.dao())
        repo.save(EntryDraft(null, "GitHub", true, EntryContent.Login("u", "short", "https://x", "m", PasswordPolicy(minLength = 20))))
        repo.save(EntryDraft(null, "메모", false, EntryContent.Note("본문")))
        repo.save(EntryDraft(null, "카드", false, EntryContent.Card(number = "4111-1111-1111-1234", cvc = "123")))
        repo.save(EntryDraft(null, "여권", false, EntryContent.Identity(issuer = "외교부", docNumber = "M1")))
        val before = repo.exportAll()
        val summariesBefore = repo.observeSummaries().first()

        val file = codec.encode(before, "backup-pw-123".toCharArray(), "1.0.0", 1)
        source.close()

        val target = openDb(otherDb)
        val restored = codec.decode(file, "backup-pw-123".toCharArray()) as BackupResult.Success
        EntryRepository(target.dao()).replaceAll(restored.entries)

        assertEquals("id·시각·내용·정책이 그대로", before, EntryRepository(target.dao()).exportAll())
        assertEquals("subtitle·last4·정책 캐시를 다시 계산했다", summariesBefore, EntryRepository(target.dao()).observeSummaries().first())
        target.close()
    }

    @Test
    fun committedV1FixtureStillDecodes() {
        // BK-07: 저장소에 커밋된 v1 파일을 현재 코드가 읽는다
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(FixtureV1.ASSET).use { it.readBytes() }
        val r = codec.decode(bytes, FixtureV1.PASSWORD.toCharArray()) as BackupResult.Success
        assertEquals(FixtureV1.entries, r.entries)
        assertEquals(BackupResult.WrongPasswordOrCorrupt, codec.decode(bytes, "wrong".toCharArray()))
    }

    @Test
    fun failedReplaceLeavesExistingDataUntouched() = runBlocking {
        val db = openDb(dbName)
        val repo = EntryRepository(db.dao())
        repo.save(EntryDraft(null, "keep me", false, EntryContent.Note("x")))
        val before = repo.exportAll()

        val dup = Entry("55555555-5555-4555-8555-555555555555", "a", false, 0, 0, null, EntryContent.Note("1"))
        assertThrows(Exception::class.java) { runBlocking { repo.replaceAll(listOf(dup, dup.copy(title = "b"))) } }

        assertEquals("트랜잭션 롤백", before, repo.exportAll())
        db.close()
    }

    // --- BK-05 ---

    private fun keyManager(meta: File) = VaultKeyManager(VaultMetaStore(meta), Argon2KeyDeriver(), AesGcmKeyWrapper(), FakeClocks())

    @Test
    fun restoreFromCorruptVaultCreatesNewVaultWithBackupEntries() {
        val meta = File(dir, "vault_meta").apply { writeBytes(ByteArray(10)) }
        val holder = VaultDatabaseHolder(context, dbName)
        val session = SessionManager(keyManager(meta), holder)
        assertEquals(SessionState.Corrupt, session.state.value)

        runBlocking {
            session.recreateVault("new master pw".toCharArray()) {
                EntryRepository(holder.requireDatabase().dao()).replaceAll(FixtureV1.entries)
            }
        }
        assertEquals(SessionState.Unlocked, session.state.value)
        assertEquals(FixtureV1.entries, runBlocking { EntryRepository(holder.requireDatabase().dao()).exportAll() })
        session.lock()

        val restarted = SessionManager(keyManager(meta), VaultDatabaseHolder(context, dbName))
        assertEquals(UnlockOutcome.Success, runBlocking { restarted.unlock("new master pw".toCharArray()) })
        restarted.lock()
    }

    @Test
    fun restoreOverExistingVaultDiscardsOldDatabase() {
        val meta = File(dir, "vault_meta")
        val holder = VaultDatabaseHolder(context, dbName)
        val session = SessionManager(keyManager(meta), holder)
        runBlocking { session.createVault("old master pw".toCharArray()) }
        runBlocking { EntryRepository(holder.requireDatabase().dao()).save(EntryDraft(null, "old", false, EntryContent.Note("x"))) }
        session.lock()

        runBlocking {
            session.recreateVault("new master pw".toCharArray()) {
                EntryRepository(holder.requireDatabase().dao()).replaceAll(FixtureV1.entries.take(1))
            }
        }
        assertEquals(listOf("GitHub"), runBlocking { EntryRepository(holder.requireDatabase().dao()).exportAll() }.map { it.title })
        session.lock()
        assertEquals(UnlockOutcome.WrongPassword, runBlocking { SessionManager(keyManager(meta), holder).unlock("old master pw".toCharArray()) })
    }

    @Test
    fun failedPopulateDoesNotLeaveSessionUnlocked() {
        val meta = File(dir, "vault_meta").apply { writeBytes(ByteArray(10)) }
        val holder = VaultDatabaseHolder(context, dbName)
        val session = SessionManager(keyManager(meta), holder)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.recreateVault("new master pw".toCharArray()) { error("기록 실패") } }
        }
        assertFalse(session.state.value == SessionState.Unlocked)
        assertThrows("DB 가 닫혀 있어야 한다", IllegalStateException::class.java) { holder.requireDatabase() }
    }

    @Test
    fun masterPasswordComparisonIsNotAnUnlockAttempt() {
        val meta = File(dir, "vault_meta")
        val session = SessionManager(keyManager(meta), VaultDatabaseHolder(context, dbName))
        runBlocking { session.createVault("master pw 1234".toCharArray()) }
        repeat(6) { assertFalse(runBlocking { session.matchesMasterPassword("other".toCharArray()) }) }
        assertTrue(runBlocking { session.matchesMasterPassword("master pw 1234".toCharArray()) })
        assertEquals(0, (VaultMetaStore(meta).read() as MetaReadResult.Present).meta.failedAttempts)
        session.lock()
    }
}
