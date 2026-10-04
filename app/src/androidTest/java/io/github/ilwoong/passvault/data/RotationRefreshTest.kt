package io.github.ilwoong.passvault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.db.VaultDatabase
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.repo.EntryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

private const val DAY = 24L * 60 * 60 * 1000

/** DM-03: 시간이 지나 낡은 hasRotationDue 를 다시 계산한다. */
@RunWith(AndroidJUnit4::class)
class RotationRefreshTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "rot-${System.nanoTime()}.db"
    private lateinit var db: VaultDatabase
    private lateinit var repo: EntryRepository
    private var now = 1_000 * DAY

    @Before
    fun setUp() {
        db = VaultDatabase.open(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, name)
        repo = EntryRepository(db.dao()) { now }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun due(id: String) = repo.observeSummaries().first().single { it.id == id }.hasRotationDue

    @Test
    fun staleCacheIsRefreshedAfterTimePasses() = runBlocking {
        val id = repo.save(EntryDraft(null, "t", false, EntryContent.Login(password = "pw", policy = PasswordPolicy(rotationDays = 30))))
        val updatedAt = repo.get(id)!!.updatedAtEpochMs
        assertFalse(due(id))

        now += 31 * DAY
        assertFalse("저장 시점 캐시는 아직 낡은 값이다", due(id))

        repo.refreshRotationDue()
        assertTrue(due(id))
        assertEquals("내용 변경이 아니다", updatedAt, repo.get(id)!!.updatedAtEpochMs)
        assertFalse("ROTATION_DUE 는 경고 등급이다", repo.observeSummaries().first().single().hasPolicyViolation)
    }

    @Test
    fun changingPasswordClearsTheFlag() = runBlocking {
        val policy = PasswordPolicy(rotationDays = 30)
        val id = repo.save(EntryDraft(null, "t", false, EntryContent.Login(password = "pw", policy = policy)))
        now += 31 * DAY
        repo.refreshRotationDue()
        assertTrue(due(id))

        repo.save(EntryDraft(id, "t", false, EntryContent.Login(password = "new-pw", policy = policy)))
        repo.refreshRotationDue()
        assertFalse(due(id))
    }

    @Test
    fun entriesWithoutRotationPolicyOrNotLoginAreNeverDue() = runBlocking {
        val noPolicy = repo.save(EntryDraft(null, "a", false, EntryContent.Login(password = "pw")))
        val noRotation = repo.save(EntryDraft(null, "b", false, EntryContent.Login(password = "pw", policy = PasswordPolicy(minLength = 1))))
        val note = repo.save(EntryDraft(null, "c", false, EntryContent.Note("n")))
        now += 10_000 * DAY
        repo.refreshRotationDue()
        assertFalse(due(noPolicy))
        assertFalse(due(noRotation))
        assertFalse(due(note))
    }
}
