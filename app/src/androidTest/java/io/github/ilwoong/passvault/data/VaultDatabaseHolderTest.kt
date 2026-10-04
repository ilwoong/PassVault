package io.github.ilwoong.passvault.data

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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** ARC-03 SessionResource 구현. LOCK-04 3 단계(DB close)와 CRY-17 */
@RunWith(AndroidJUnit4::class)
class VaultDatabaseHolderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "holder-${System.nanoTime()}.db"
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun opensOnUnlockAndClosesOnLock() = runBlocking<Unit> {
        val holder = VaultDatabaseHolder(context, name)
        holder.onUnlocked(key)
        val db = holder.requireDatabase()
        EntryRepository(db.dao()).save(EntryDraft(null, "t", false, EntryContent.Note("b")))
        val passphrase = checkNotNull(db.passphrase)

        holder.onLocked()

        assertTrue(!db.isOpen)
        assertTrue("CRY-17: close 후 패스프레이즈 제로화", passphrase.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { holder.requireDatabase() }
    }

    @Test
    fun wrongKeySurfacesAtUnlockAndTouchesNothing() {
        VaultDatabaseHolder(context, name).apply {
            onUnlocked(key)
            onLocked()
        }
        val file = context.getDatabasePath(name)
        val before = file.readBytes()

        val holder = VaultDatabaseHolder(context, name)
        val wrong = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val failure = runCatching { holder.onUnlocked(wrong) }

        assertTrue("강제 오픈으로 해제 시점에 드러나야 한다", failure.isFailure)
        assertThrows(IllegalStateException::class.java) { holder.requireDatabase() }
        assertArrayEquals("ARC-06: 실패해도 파일을 건드리지 않는다", before, file.readBytes())

        holder.onUnlocked(key)
        assertEquals(true, holder.requireDatabase().isOpen)
        holder.onLocked()
    }
}
