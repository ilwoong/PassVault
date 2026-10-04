package io.github.ilwoong.passvault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.db.VaultDatabase
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.EntrySummary
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.repo.EntryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

private const val DAY = 24L * 60 * 60 * 1000

/** TST-07: DM-11 쓰기 불변식과 캐시 컬럼 */
@RunWith(AndroidJUnit4::class)
class EntryRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "repo-${System.nanoTime()}.db"
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

    // --- 왕복 ---

    @Test
    fun allFourTypesRoundTrip() = runBlocking {
        val contents = listOf(
            EntryContent.Login("user", "pw", "https://x", "memo", PasswordPolicy(minLength = 2, upperRule = CharClassRule.FORBIDDEN, rawNote = "원문")),
            EntryContent.Note("본문"),
            EntryContent.Card("홍길동", "4111111111111111", "VISA", 12, 2030, "123", "0000", "memo"),
            EntryContent.Identity("여권", "홍길동", "M12345678", "외교부", "2020-01-01", "2030-01-01", "memo"),
        )
        for (c in contents) {
            val id = repo.save(EntryDraft(null, "t", true, c))
            val loaded = repo.get(id)!!
            assertEquals(c, loaded.content)
            assertEquals("t", loaded.title)
            assertTrue(loaded.isFavorite)
        }
    }

    // --- subtitle / last4 ---

    @Test
    fun loginSubtitleIsUsernameAndBlankBecomesNull() = runBlocking {
        val a = repo.save(draft(EntryContent.Login(username = "alice")))
        val b = repo.save(draft(EntryContent.Login(username = "  ")))
        assertEquals("alice", summary(a).subtitle)
        assertNull(summary(b).subtitle)
    }

    @Test
    fun cardNumberIsNormalizedAndSubtitleShowsOnlyLast4() = runBlocking {
        val id = repo.save(draft(EntryContent.Card(number = "4111-1111 1111-1234")))
        assertEquals("•••• 1234", summary(id).subtitle)
        assertEquals("4111111111111234", (repo.get(id)!!.content as EntryContent.Card).number)
    }

    @Test
    fun shortCardNumberProducesNoSubtitle() = runBlocking {
        val id = repo.save(draft(EntryContent.Card(number = "1234")))
        assertNull("4자리 번호를 목록에 드러내면 안 된다", summary(id).subtitle)
    }

    @Test
    fun identitySubtitleIsIssuerAndNoteHasNone() = runBlocking {
        assertEquals("외교부", summary(repo.save(draft(EntryContent.Identity(issuer = "외교부")))).subtitle)
        assertNull(summary(repo.save(draft(EntryContent.Note("x")))).subtitle)
    }

    // --- 정책 캐시 ---

    @Test
    fun policyViolationIsCachedAtSaveAndRecomputedOnUpdate() = runBlocking {
        val policy = PasswordPolicy(minLength = 10)
        val id = repo.save(draft(EntryContent.Login(password = "short", policy = policy)))
        assertTrue(summary(id).hasPolicyViolation)

        repo.save(draft(EntryContent.Login(password = "long-enough-pw", policy = policy), id))
        assertFalse(summary(id).hasPolicyViolation)
    }

    @Test
    fun noPolicyMeansNoFlags() = runBlocking {
        val id = repo.save(draft(EntryContent.Login(password = "x")))
        assertFalse(summary(id).hasPolicyViolation)
        assertFalse(summary(id).hasRotationDue)
    }

    @Test
    fun rotationDueUsesPasswordChangeTimeNotEntryUpdateTime() = runBlocking {
        val policy = PasswordPolicy(rotationDays = 30)
        val id = repo.save(draft(EntryContent.Login(password = "pw", policy = policy)))
        assertFalse(summary(id).hasRotationDue)

        // 31일 뒤 메모만 고친다 — 비밀번호 변경 시각은 그대로라 주기 경과
        now += 31 * DAY
        repo.save(draft(EntryContent.Login(password = "pw", memo = "edited", policy = policy), id))
        assertTrue(summary(id).hasRotationDue)
        assertFalse("ROTATION_DUE 는 경고 등급이다", summary(id).hasPolicyViolation)

        // 비밀번호를 바꾸면 다시 0일
        repo.save(draft(EntryContent.Login(password = "new-pw", policy = policy), id))
        assertFalse(summary(id).hasRotationDue)
    }

    @Test
    fun passwordUpdatedAtChangesOnlyWhenPasswordChanges() = runBlocking {
        val t0 = now
        val id = repo.save(draft(EntryContent.Login(password = "pw")))
        assertEquals(t0, repo.get(id)!!.passwordUpdatedAtEpochMs)

        now += DAY
        repo.save(draft(EntryContent.Login(password = "pw", username = "u"), id))
        assertEquals(t0, repo.get(id)!!.passwordUpdatedAtEpochMs)

        now += DAY
        repo.save(draft(EntryContent.Login(password = "pw2"), id))
        assertEquals(now, repo.get(id)!!.passwordUpdatedAtEpochMs)
    }

    @Test
    fun removingPolicyDeletesPolicyRow() = runBlocking {
        val id = repo.save(draft(EntryContent.Login(password = "pw", policy = PasswordPolicy(minLength = 1))))
        repo.save(draft(EntryContent.Login(password = "pw", policy = null), id))

        assertNull((repo.get(id)!!.content as EntryContent.Login).policy)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM password_policy").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    // --- 시각 ---

    @Test
    fun updateKeepsCreatedAtAndBumpsUpdatedAt() = runBlocking {
        val t0 = now
        val id = repo.save(draft(EntryContent.Note("a")))
        now += DAY
        repo.save(draft(EntryContent.Note("b"), id))

        val e = repo.get(id)!!
        assertEquals(t0, e.createdAtEpochMs)
        assertEquals(now, e.updatedAtEpochMs)
    }

    // --- 거부 ---

    @Test
    fun typeCannotChange() = runBlocking {
        val id = repo.save(draft(EntryContent.Note("a")))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.save(draft(EntryContent.Login(password = "x"), id)) }
        }
        assertEquals(EntryContent.Note("a"), repo.get(id)!!.content)
    }

    @Test
    fun updatingMissingEntryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.save(draft(EntryContent.Note("a"), "no-such-id")) }
        }
    }

    @Test
    fun blankTitleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.save(EntryDraft(null, " ", false, EntryContent.Note("a"))) }
        }
    }

    // --- 목록·삭제 ---

    @Test
    fun summariesListFavoritesFirstThenTitleIgnoringCase() = runBlocking {
        repo.save(EntryDraft(null, "banana", false, EntryContent.Note("")))
        repo.save(EntryDraft(null, "Apple", false, EntryContent.Note("")))
        repo.save(EntryDraft(null, "zebra", true, EntryContent.Note("")))
        assertEquals(listOf("zebra", "Apple", "banana"), repo.observeSummaries().first().map { it.title })
    }

    @Test
    fun deleteRemovesEntry() = runBlocking {
        val id = repo.save(draft(EntryContent.Note("a")))
        repo.delete(id)
        assertNull(repo.get(id))
        assertTrue(repo.observeSummaries().first().isEmpty())
    }

    private fun draft(content: EntryContent, id: String? = null) = EntryDraft(id, "title", false, content)

    private suspend fun summary(id: String): EntrySummary = repo.observeSummaries().first().single { it.id == id }
}
