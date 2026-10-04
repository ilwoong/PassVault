package io.github.ilwoong.passvault.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.db.VaultDatabaseHolder
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.repo.EntryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TST-11 (세션 쪽), TST-03 (비밀번호 변경), CRY-12 ~ CRY-15.
 *
 * Keystore 생체 키는 실제 생체 인증 없이는 쓸 수 없다. 여기서는 BiometricKeyStore 가 하는 일
 * (인증된 AES-GCM Cipher 로 감싸고 풀기)을 소프트웨어 키로 흉내 내 세션 쪽 규칙을 검증한다.
 */
@RunWith(AndroidJUnit4::class)
class BiometricSessionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var metaFile: File
    private val dbName = "bio-${System.nanoTime()}.db"
    private val clocks = FakeClocks()
    private val pw = "correct horse battery"

    /** "BioKey" 흉내. 무효화되면 풀지 못한다. */
    private val bioKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private var bioInvalidated = false
    private val fakeWrap: (ByteArray) -> ByteArray = { vk ->
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, bioKey) }
        c.iv + c.doFinal(vk)
    }
    private val fakeUnwrap: (ByteArray) -> ByteArray? = { blob ->
        if (bioInvalidated) {
            null
        } else {
            runCatching {
                Cipher.getInstance("AES/GCM/NoPadding")
                    .apply { init(Cipher.DECRYPT_MODE, bioKey, GCMParameterSpec(128, blob, 0, 12)) }
                    .doFinal(blob, 12, blob.size - 12)
            }.getOrNull()
        }
    }

    private class Resource : SessionResource {
        var lastKey: ByteArray? = null
        override fun onUnlocked(vaultKey: ByteArray) {
            lastKey = vaultKey.copyOf()
        }
        override fun onLocked() = Unit
    }

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "bio-${System.nanoTime()}").apply { mkdirs() }
        metaFile = File(dir, "vault_meta")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        context.deleteDatabase(dbName)
    }

    private fun keyManager() = VaultKeyManager(VaultMetaStore(metaFile), Argon2KeyDeriver(), AesGcmKeyWrapper(), clocks)
    private fun session(resource: SessionResource = Resource()) = SessionManager(keyManager(), resource)

    private fun createdAndUnlocked(resource: SessionResource = Resource()): SessionManager =
        session(resource).also { runBlocking { it.createVault(pw.toCharArray()) } }

    // --- CRY-12 / CRY-13 ---

    @Test
    fun enrolledBiometricUnlocksWithSameVaultKeyAfterRestart() {
        val first = Resource()
        val s = createdAndUnlocked(first)
        assertFalse(s.isBiometricEnrolled())
        assertTrue(s.enableBiometric(fakeWrap))
        assertTrue("설정값 없이 vault_meta 에서 파생된다 (DM-02)", s.isBiometricEnrolled())
        assertEquals(60, s.biometricBlob()!!.size)

        val second = Resource()
        val restarted = session(second)
        assertTrue(restarted.isBiometricEnrolled())
        assertEquals(UnlockOutcome.Success, runBlocking { restarted.unlockWithBiometric(fakeUnwrap) })
        assertEquals(SessionState.Unlocked, restarted.state.value)
        assertArrayEquals(first.lastKey, second.lastKey)
    }

    @Test
    fun cannotEnrollWhileLocked() {
        val s = createdAndUnlocked()
        s.lock()
        assertFalse(s.enableBiometric(fakeWrap))
        assertFalse(s.isBiometricEnrolled())
    }

    @Test
    fun invalidatedBiometricTurnsItselfOffAndPasswordStillWorks() {
        createdAndUnlocked().enableBiometric(fakeWrap)
        bioInvalidated = true // 생체 정보 추가 등록 (SEC-11)

        val s = session()
        assertEquals(UnlockOutcome.BiometricInvalidated, runBlocking { s.unlockWithBiometric(fakeUnwrap) })
        assertEquals(SessionState.Locked, s.state.value)
        assertFalse("CRY-13 4·5 단계: 래핑을 지운다", s.isBiometricEnrolled())
        assertEquals(UnlockOutcome.Success, runBlocking { s.unlock(pw.toCharArray()) })
    }

    @Test
    fun biometricWorksDuringPasswordLockoutAndResetsCounter() {
        createdAndUnlocked().enableBiometric(fakeWrap)
        val s = session()
        repeat(5) { runBlocking { s.unlock("wrong".toCharArray()) } }
        assertTrue(s.lockoutRemainingMs() > 0)

        assertEquals(UnlockOutcome.Success, runBlocking { s.unlockWithBiometric(fakeUnwrap) })
        assertEquals("성공한 해제는 카운터를 초기화한다", 0, s.lockoutRemainingMs())
    }

    // --- UX-03 재인증 ---

    @Test
    fun reauthenticationChecksPasswordWithoutChangingSessionAndCountsFailures() {
        val s = createdAndUnlocked()
        assertEquals(UnlockOutcome.Success, runBlocking { s.reauthenticate(pw.toCharArray()) })
        assertEquals(SessionState.Unlocked, s.state.value)

        repeat(5) { assertEquals(UnlockOutcome.WrongPassword, runBlocking { s.reauthenticate("guess$it".toCharArray()) }) }
        assertTrue("빌린 기기에서 재인증으로 추측하는 것도 백오프를 받는다", runBlocking { s.reauthenticate(pw.toCharArray()) } is UnlockOutcome.LockedOut)
        assertEquals(SessionState.Unlocked, s.state.value)
    }

    // --- CRY-15 / TST-03 ---

    @Test
    fun changingPasswordKeepsVaultKeyAndDropsBiometric() {
        val first = Resource()
        val s = createdAndUnlocked(first)
        s.enableBiometric(fakeWrap)
        val createdAt = (VaultMetaStore(metaFile).read() as MetaReadResult.Present).meta.vaultCreatedAtEpochMs

        assertEquals(UnlockOutcome.Success, runBlocking { s.changeMasterPassword(pw.toCharArray(), "brand new password".toCharArray()) })
        assertEquals("VK 가 그대로라 세션은 계속 열려 있다", SessionState.Unlocked, s.state.value)
        assertFalse("CRY-15 6 단계", s.isBiometricEnrolled())
        assertEquals(createdAt, (VaultMetaStore(metaFile).read() as MetaReadResult.Present).meta.vaultCreatedAtEpochMs)

        val second = Resource()
        val restarted = session(second)
        assertEquals(UnlockOutcome.WrongPassword, runBlocking { restarted.unlock(pw.toCharArray()) })
        assertEquals(UnlockOutcome.Success, runBlocking { restarted.unlock("brand new password".toCharArray()) })
        assertArrayEquals("DB 를 재암호화하지 않는다", first.lastKey, second.lastKey)
    }

    @Test
    fun changingPasswordWithWrongCurrentFailsAndChangesNothing() {
        val s = createdAndUnlocked()
        val before = metaFile.readBytes()
        assertEquals(UnlockOutcome.WrongPassword, runBlocking { s.changeMasterPassword("nope".toCharArray(), "x".repeat(12).toCharArray()) })
        val after = (VaultMetaStore(metaFile).read() as MetaReadResult.Present).meta
        assertEquals("실패 횟수만 늘어난다", 1, after.failedAttempts)
        assertArrayEquals(VaultMetaStore.decode(before)!!.wrappedVkByMk, after.wrappedVkByMk)
    }

    @Test
    fun entriesSurvivePasswordChange() {
        val holder = VaultDatabaseHolder(context, dbName)
        val s = SessionManager(keyManager(), holder)
        runBlocking { s.createVault(pw.toCharArray()) }
        val id = runBlocking {
            EntryRepository(holder.requireDatabase().dao())
                .save(EntryDraft(null, "GitHub", false, EntryContent.Login(password = "secret")))
        }

        runBlocking { s.changeMasterPassword(pw.toCharArray(), "brand new password".toCharArray()) }
        s.lock()
        assertEquals(UnlockOutcome.Success, runBlocking { s.unlock("brand new password".toCharArray()) })

        val e = runBlocking { EntryRepository(holder.requireDatabase().dao()).get(id) }
        assertEquals("secret", (e!!.content as EntryContent.Login).password)
        s.lock()
    }

    // --- UX-01 4 단계 ---

    @Test
    fun justCreatedIsReportedOnceAndNotAfterRestart() {
        val s = createdAndUnlocked()
        assertTrue(s.consumeJustCreated())
        assertFalse(s.consumeJustCreated())
        assertFalse(session().consumeJustCreated())
    }

    @Test
    fun noBiometricWrapMeansNoBiometricUnlock() {
        createdAndUnlocked()
        val s = session()
        assertNull(s.biometricBlob())
        assertEquals(UnlockOutcome.BiometricInvalidated, runBlocking { s.unlockWithBiometric(fakeUnwrap) })
        assertEquals(SessionState.Locked, s.state.value)
    }
}
