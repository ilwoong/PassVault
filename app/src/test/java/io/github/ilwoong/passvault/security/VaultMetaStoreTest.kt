package io.github.ilwoong.passvault.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/** DM-01 레이아웃·읽기 3분류, TST-03 원자적 교체 */
class VaultMetaStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var tmpFile: File
    private lateinit var store: VaultMetaStore

    @Before
    fun setUp() {
        file = File(tmp.root, "vault_meta")
        tmpFile = File(tmp.root, "vault_meta.tmp")
        store = VaultMetaStore(file)
    }

    // --- 읽기·쓰기 ---

    @Test
    fun absentWhenNoFile() {
        assertEquals(MetaReadResult.Absent, store.read())
    }

    @Test
    fun roundTripWithoutBiometricWrap() {
        val m = meta(seed = 1, bio = false)
        store.write(m)
        assertMetaEquals(m, present())
        assertNull(present().wrappedVkByBio)
    }

    @Test
    fun roundTripWithBiometricWrap() {
        val m = meta(seed = 2, bio = true)
        store.write(m)
        assertMetaEquals(m, present())
    }

    @Test
    fun fileIsExactly188Bytes() {
        store.write(meta(1))
        assertEquals(188L, file.length())
    }

    @Test
    fun overwriteReplacesPreviousContent() {
        store.write(meta(1))
        val b = meta(2, bio = true)
        store.write(b)
        assertMetaEquals(b, present())
    }

    // --- Corrupt 는 절대 Absent 가 아니다 ---

    @Test
    fun emptyFileIsCorruptNotAbsent() {
        file.writeBytes(ByteArray(0))
        assertEquals(MetaReadResult.Corrupt, store.read())
    }

    @Test
    fun truncatedOrExtendedFileIsCorrupt() {
        store.write(meta(1))
        val valid = file.readBytes()
        for (bytes in listOf(valid.copyOf(187), valid + 0)) {
            file.writeBytes(bytes)
            assertEquals(MetaReadResult.Corrupt, store.read())
        }
    }

    @Test
    fun badMagicIsCorrupt() = assertCorruptAfter { it.put(0, 'X'.code.toByte()) }

    @Test
    fun unknownVersionIsCorrupt() = assertCorruptAfter { it.put(6, 2) }

    @Test
    fun invalidBiometricFlagIsCorrupt() = assertCorruptAfter { it.put(7, 2) }

    @Test
    fun kdfParamsOutsideCalibrationRangeAreCorrupt() {
        assertCorruptAfter { it.putInt(24, KdfParams.DEFAULT_MEMORY_KIB * 2) } // m 과대 (비트 뒤집힘)
        assertCorruptAfter { it.putInt(24, KdfParams.MIN_MEMORY_KIB - 1) }     // m 과소
        assertCorruptAfter { it.putInt(28, 0) }                                // t = 0
        assertCorruptAfter { it.putInt(28, KdfParams.MAX_ITERATIONS + 1) }     // t 과대
        assertCorruptAfter { it.putInt(32, 1) }                                // p ≠ 2
    }

    // --- TST-03: 원자적 교체 ---

    @Test
    fun crashAfterTmpWrittenBeforeRenameKeepsOldVersion() {
        val a = meta(1)
        store.write(a)
        // 1·2 단계(tmp 쓰기 + fsync)는 끝났고 3 단계(rename) 직전에 프로세스가 죽은 상태
        tmpFile.writeBytes(VaultMetaStore.encode(meta(2, bio = true)))

        assertMetaEquals(a, present())
    }

    @Test
    fun crashMidTmpWriteKeepsOldVersion() {
        val a = meta(1)
        store.write(a)
        // tmp 를 쓰는 도중에 죽어 절반만 남은 상태
        tmpFile.writeBytes(VaultMetaStore.encode(meta(2)).copyOf(80))

        assertMetaEquals(a, present())
    }

    @Test
    fun leftoverTmpDoesNotBreakNextWrite() {
        store.write(meta(1))
        tmpFile.writeBytes(byteArrayOf(1, 2, 3))

        val b = meta(2)
        store.write(b)

        assertMetaEquals(b, present())
        assertFalse(tmpFile.exists())
    }

    @Test
    fun firstWriteLeavesNoTmpBehind() {
        store.write(meta(1))
        assertTrue(file.exists())
        assertFalse(tmpFile.exists())
    }

    // --- 도우미 ---

    private fun meta(seed: Int, bio: Boolean = false) = VaultMeta(
        kdfSalt = ByteArray(SALT_BYTES) { (seed + it).toByte() },
        kdfParams = KdfParams(KdfParams.DEFAULT_MEMORY_KIB, KdfParams.DEFAULT_ITERATIONS, KdfParams.PARALLELISM),
        wrappedVkByMk = ByteArray(60) { (seed * 3 + it).toByte() },
        wrappedVkByBio = if (bio) ByteArray(60) { (seed * 5 + it).toByte() } else null,
        failedAttempts = seed,
        lockoutUntilEpochMs = seed * 1_000L,
        vaultCreatedAtEpochMs = seed * 7L,
        lockoutBootCount = seed * 11,
        lockoutUntilElapsedMs = seed * 13L,
    )

    private fun present(): VaultMeta = (store.read() as MetaReadResult.Present).meta

    private fun assertCorruptAfter(mutate: (ByteBuffer) -> Unit) {
        store.write(meta(1))
        val bytes = file.readBytes()
        mutate(ByteBuffer.wrap(bytes))
        file.writeBytes(bytes)
        assertEquals(MetaReadResult.Corrupt, store.read())
    }

    private fun assertMetaEquals(e: VaultMeta, a: VaultMeta) {
        assertArrayEquals(e.kdfSalt, a.kdfSalt)
        assertEquals(e.kdfParams, a.kdfParams)
        assertArrayEquals(e.wrappedVkByMk, a.wrappedVkByMk)
        assertArrayEquals(e.wrappedVkByBio, a.wrappedVkByBio)
        assertEquals(e.failedAttempts, a.failedAttempts)
        assertEquals(e.lockoutUntilEpochMs, a.lockoutUntilEpochMs)
        assertEquals(e.vaultCreatedAtEpochMs, a.vaultCreatedAtEpochMs)
        assertEquals(e.lockoutBootCount, a.lockoutBootCount)
        assertEquals(e.lockoutUntilElapsedMs, a.lockoutUntilElapsedMs)
    }
}
