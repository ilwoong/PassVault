package io.github.ilwoong.passvault.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.db.VaultDatabase
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.EntryType
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.repo.EntryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** UX-04 검색·필터·즐겨찾기, UX-05 다시 읽기, NFR-02 */
@RunWith(AndroidJUnit4::class)
class EntryListQueryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "list-${System.nanoTime()}.db"
    private lateinit var db: VaultDatabase
    private lateinit var repo: EntryRepository

    @Before
    fun setUp() {
        db = VaultDatabase.open(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, name)
        repo = EntryRepository(db.dao())
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun titles(type: EntryType? = null, q: String = "") =
        repo.observeSummaries(type, q).first().map { it.title }

    @Test
    fun searchMatchesTitleAndSubtitleIgnoringAsciiCase() = runBlocking {
        repo.save(EntryDraft(null, "GitHub", false, EntryContent.Login(username = "octocat")))
        repo.save(EntryDraft(null, "은행", false, EntryContent.Login(username = "kim")))
        repo.save(EntryDraft(null, "메모", false, EntryContent.Note("github 이라는 단어가 본문에만 있음")))

        assertEquals(listOf("GitHub"), titles(q = "github"))
        assertEquals("subtitle(사용자명)도 찾는다", listOf("GitHub"), titles(q = "OCTO"))
        assertEquals(listOf("은행"), titles(q = "은"))
        assertTrue("비밀 필드(본문)는 검색하지 않는다", titles(q = "단어").isEmpty())
    }

    @Test
    fun likeWildcardsInQueryAreLiteral() = runBlocking {
        repo.save(EntryDraft(null, "100% off", false, EntryContent.Note("")))
        repo.save(EntryDraft(null, "1000 off", false, EntryContent.Note("")))
        repo.save(EntryDraft(null, "a_b", false, EntryContent.Note("")))
        repo.save(EntryDraft(null, "axb", false, EntryContent.Note("")))

        assertEquals(listOf("100% off"), titles(q = "100%"))
        assertEquals(listOf("a_b"), titles(q = "a_b"))
    }

    @Test
    fun typeFilter() = runBlocking {
        repo.save(EntryDraft(null, "L", false, EntryContent.Login()))
        repo.save(EntryDraft(null, "C", false, EntryContent.Card()))
        assertEquals(listOf("C"), titles(type = EntryType.CARD))
        assertEquals(listOf("C", "L"), titles())
    }

    @Test
    fun favoriteToggleTouchesOnlyTheFlag() = runBlocking {
        val policy = PasswordPolicy(minLength = 30)
        val id = repo.save(EntryDraft(null, "t", false, EntryContent.Login(password = "pw", policy = policy)))
        val before = repo.get(id)!!

        repo.setFavorite(id, true)

        val after = repo.get(id)!!
        assertTrue(after.isFavorite)
        assertEquals("상세·정책이 CASCADE 로 지워지면 안 된다", before.content, after.content)
        assertEquals("내용 변경이 아니다", before.updatedAtEpochMs, after.updatedAtEpochMs)
        assertTrue("정책 캐시도 그대로", repo.observeSummaries().first().single().hasPolicyViolation)
    }

    @Test
    fun observeEmitsAgainAfterSaveAndNullAfterDelete() = runBlocking {
        val id = repo.save(EntryDraft(null, "t", false, EntryContent.Note("v1")))
        val seen = mutableListOf<String?>()
        val job = launch { repo.observe(id).take(3).toList().forEach { seen += (it?.content as? EntryContent.Note)?.body } }
        kotlinx.coroutines.delay(300)
        repo.save(EntryDraft(id, "t", false, EntryContent.Note("v2")))
        kotlinx.coroutines.delay(300)
        repo.delete(id)
        job.join()
        assertEquals(listOf("v1", "v2", null), seen)
    }

    @Test
    fun deletedEntryReadsAsNull() = runBlocking {
        val id = repo.save(EntryDraft(null, "t", false, EntryContent.Note("v")))
        repo.delete(id)
        assertNull(repo.observe(id).first())
    }

    // --- NFR-02 ---

    @Test
    fun listAndSearchWithin100msAt1000Entries() = runBlocking {
        repeat(1_000) { i ->
            val c = when (i % 4) {
                0 -> EntryContent.Login(username = "user$i@example.com", password = "pw-$i", policy = PasswordPolicy(minLength = 8))
                1 -> EntryContent.Note("본문 $i")
                2 -> EntryContent.Card(number = "4111111111${"%06d".format(i)}")
                else -> EntryContent.Identity(issuer = "기관 $i", docNumber = "D$i")
            }
            repo.save(EntryDraft(null, "항목 $i", i % 10 == 0, c))
        }
        repo.observeSummaries().first() // 준비 (쿼리 컴파일·캐시)

        val listMs = timed { assertEquals(1_000, repo.observeSummaries().first().size) }
        val searchMs = timed { assertTrue(repo.observeSummaries(null, "user99").first().isNotEmpty()) }
        val filterMs = timed { assertEquals(250, repo.observeSummaries(EntryType.CARD, "").first().size) }
        Log.i("NFR-02", "list=${listMs}ms search=${searchMs}ms filter=${filterMs}ms (1000 entries)")

        assertTrue("목록 ${listMs}ms", listMs < 100)
        assertTrue("검색 ${searchMs}ms", searchMs < 100)
        assertTrue("필터 ${filterMs}ms", filterMs < 100)
    }

    private inline fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }
}
