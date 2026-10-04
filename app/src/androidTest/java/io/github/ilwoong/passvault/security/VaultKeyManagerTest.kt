package io.github.ilwoong.passvault.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** TST-01: 실제 Argon2id + AES-GCM + 파일 저장소로 CRY-10·CRY-11 을 끝까지 돌린다. */
@RunWith(AndroidJUnit4::class)
class VaultKeyManagerTest {

    private lateinit var dir: File
    private lateinit var metaFile: File
    private lateinit var manager: VaultKeyManager

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "vkm-${System.nanoTime()}").apply { mkdirs() }
        metaFile = File(dir, "vault_meta")
        manager = VaultKeyManager(VaultMetaStore(metaFile), Argon2KeyDeriver(), AesGcmKeyWrapper())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun createThenUnlockRestoresSameVaultKey() {
        val created = manager.createVault("correct horse battery".toCharArray())
        assertEquals(KEY_BYTES, created.size)

        val unlocked = manager.unlock("correct horse battery".toCharArray())

        assertArrayEquals(created, (unlocked as UnlockResult.Success).vaultKey)
    }

    @Test
    fun unicodePasswordRoundTrips() {
        val pw = "한글비밀번호🔐key"
        val created = manager.createVault(pw.toCharArray())
        assertArrayEquals(created, (manager.unlock(pw.toCharArray()) as UnlockResult.Success).vaultKey)
    }

    @Test
    fun wrongPasswordIsAResultNotAnException() {
        manager.createVault("right password".toCharArray())
        assertEquals(UnlockResult.WrongPassword, manager.unlock("wrong password".toCharArray()))
    }

    @Test
    fun unlockWithoutVaultReportsNoVault() {
        assertEquals(UnlockResult.NoVault, manager.unlock("anything".toCharArray()))
    }

    @Test
    fun tamperedWrappedKeyFailsLikeWrongPassword() {
        manager.createVault("pw-123456789".toCharArray())
        patchMeta { it.put(40, (it.get(40).toInt() xor 0x01).toByte()) } // wrappedVkByMk 암호문 영역

        assertEquals(UnlockResult.WrongPassword, manager.unlock("pw-123456789".toCharArray()))
    }

    @Test
    fun tamperedSaltFailsLikeWrongPassword() {
        manager.createVault("pw-123456789".toCharArray())
        patchMeta { it.put(8, (it.get(8).toInt() xor 0x01).toByte()) }

        assertEquals(UnlockResult.WrongPassword, manager.unlock("pw-123456789".toCharArray()))
    }

    @Test
    fun tamperedKdfParamWithinValidRangeFailsLikeWrongPassword() {
        manager.createVault("pw-123456789".toCharArray())
        // 범위 안의 다른 값으로 바꿔 decode 는 통과시키고 언래핑에서 걸리게 한다 (CRY-03 AAD + MK 변화)
        patchMeta {
            val m = it.getInt(24)
            it.putInt(24, if (m == KdfParams.DEFAULT_MEMORY_KIB) KdfParams.MIN_MEMORY_KIB else KdfParams.DEFAULT_MEMORY_KIB)
        }

        assertEquals(UnlockResult.WrongPassword, manager.unlock("pw-123456789".toCharArray()))
    }

    @Test
    fun corruptMetaIsReportedAsCorrupt() {
        metaFile.writeBytes(ByteArray(10))
        assertEquals(UnlockResult.Corrupt, manager.unlock("anything".toCharArray()))
    }

    @Test
    fun createRefusesToOverwriteExistingVault() {
        val original = manager.createVault("first".toCharArray())

        assertThrows(IllegalStateException::class.java) { manager.createVault("second".toCharArray()) }

        assertArrayEquals(original, (manager.unlock("first".toCharArray()) as UnlockResult.Success).vaultKey)
    }

    @Test
    fun createRefusesToOverwriteCorruptMeta() {
        // DM-01: Corrupt 위에 새 금고를 만들면 복구 가능성이 영구히 사라진다.
        val damaged = ByteArray(100) { it.toByte() }
        metaFile.writeBytes(damaged)

        assertThrows(IllegalStateException::class.java) { manager.createVault("new".toCharArray()) }

        assertArrayEquals(damaged, metaFile.readBytes())
    }

    @Test
    fun createdMetaIsReadableAndHasNoBiometricWrap() {
        manager.createVault("pw-123456789".toCharArray())
        val meta = (VaultMetaStore(metaFile).read() as MetaReadResult.Present).meta
        assertEquals(null, meta.wrappedVkByBio)
        assertEquals(0, meta.failedAttempts)
        assertTrue(meta.vaultCreatedAtEpochMs > 0)
    }

    private fun patchMeta(mutate: (ByteBuffer) -> Unit) {
        val bytes = metaFile.readBytes()
        mutate(ByteBuffer.wrap(bytes))
        metaFile.writeBytes(bytes)
    }
}
