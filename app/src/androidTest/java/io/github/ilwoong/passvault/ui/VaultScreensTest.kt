package io.github.ilwoong.passvault.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntrySummary
import io.github.ilwoong.passvault.data.model.EntryType
import io.github.ilwoong.passvault.ui.common.withNoPersonalizedLearning
import io.github.ilwoong.passvault.ui.detail.EntryDetailScreen
import io.github.ilwoong.passvault.ui.edit.EntryEditScreen
import io.github.ilwoong.passvault.ui.edit.EntryForm
import io.github.ilwoong.passvault.ui.edit.FieldKey
import io.github.ilwoong.passvault.ui.list.EntryListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** TST-14 (화면 규칙), UX-06 IME 학습 차단 */
@RunWith(AndroidJUnit4::class)
class VaultScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun s(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private val secret = "Sup3r-S3cret-Value"
    private val login = Entry(
        id = "id-1", title = "GitHub", isFavorite = false, createdAtEpochMs = 0, updatedAtEpochMs = 0,
        passwordUpdatedAtEpochMs = null,
        content = EntryContent.Login(username = "octocat", password = secret, url = "https://github.com"),
    )

    // --- UX-05 마스킹 ---

    @Test
    fun secretIsMaskedUntilRevealedAndNonSecretIsShown() {
        compose.setContent { EntryDetailScreen(login, onBack = {}, onEdit = {}, onDelete = {}, onCopy = {}) }

        compose.onAllNodesWithText(secret).assertCountEquals(0)
        compose.onNodeWithText("octocat").assertIsDisplayed()
        compose.onNodeWithText(s(R.string.masked)).assertIsDisplayed()

        compose.onNodeWithText(s(R.string.action_show)).performClick()
        compose.onNodeWithText(secret).assertIsDisplayed()

        compose.onNodeWithText(s(R.string.action_hide)).performClick()
        compose.onAllNodesWithText(secret).assertCountEquals(0)
    }

    @Test
    fun revealStateResetsWhenScreenIsRecreated() {
        var show by mutableStateOf(true)
        compose.setContent {
            if (show) EntryDetailScreen(login, onBack = {}, onEdit = {}, onDelete = {}, onCopy = {})
        }
        compose.onNodeWithText(s(R.string.action_show)).performClick()
        compose.onNodeWithText(secret).assertIsDisplayed()

        show = false
        compose.waitForIdle()
        show = true
        compose.waitForIdle()

        compose.onAllNodesWithText(secret).assertCountEquals(0)
    }

    @Test
    fun copyHandsOverTheRealValueEvenWhileMasked() {
        val copied = mutableListOf<String>()
        compose.setContent { EntryDetailScreen(login, onBack = {}, onEdit = {}, onDelete = {}, onCopy = { copied += it }) }
        // 행 순서: 사용자명, 비밀번호, URL — 두 번째 '복사'가 비밀번호
        compose.onAllNodesWithText(s(R.string.action_copy))[1].performClick()
        assertEquals(listOf(secret), copied)
    }

    @Test
    fun deleteAsksForConfirmation() {
        var deleted = 0
        compose.setContent { EntryDetailScreen(login, onBack = {}, onEdit = {}, onDelete = { deleted++ }, onCopy = {}) }
        compose.onNodeWithContentDescription(s(R.string.cd_delete)).performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText(s(R.string.delete_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_delete)).performClick()
        assertEquals(1, deleted)
    }

    // --- UX-04 목록에는 비밀이 없다 ---

    @Test
    fun listShowsTitleAndSubtitleOnly() {
        val rows = listOf(EntrySummary("id-1", EntryType.LOGIN, "GitHub", "octocat", false, false, false))
        compose.setContent {
            EntryListScreen(rows, "", null, {}, {}, {}, { _, _ -> }, {}, {})
        }
        compose.onNodeWithText("GitHub").assertIsDisplayed()
        compose.onNodeWithText("octocat").assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.cd_lock)).assertIsDisplayed()
    }

    // --- UX-06 편집 ---

    @Test
    fun saveRequiresTitleAndValidFields() {
        val form = EntryForm(EntryType.CARD, null)
        compose.setContent { EntryEditScreen(form, saving = false, failed = false, onSave = {}, onExit = {}) }
        compose.onNodeWithText(s(R.string.action_save)).assertIsNotEnabled()

        form.fields.first { it.key == FieldKey.TITLE }.state.setTextAndPlaceCursorAtEnd("내 카드")
        compose.onNodeWithText(s(R.string.action_save)).assertIsEnabled()

        form.fields.first { it.key == FieldKey.EXP_MONTH }.state.setTextAndPlaceCursorAtEnd("13")
        compose.onNodeWithText(s(R.string.action_save)).assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.invalid_month)).assertIsDisplayed()
    }

    @Test
    fun leavingWithChangesAsksBeforeDiscarding() {
        val form = EntryForm(EntryType.LOGIN, login)
        var exited = 0
        compose.setContent { EntryEditScreen(form, saving = false, failed = false, onSave = {}, onExit = { exited++ }) }

        form.fields.first { it.key == FieldKey.PASSWORD }.state.setTextAndPlaceCursorAtEnd("changed")
        compose.onNodeWithContentDescription(s(R.string.cd_back)).performClick()
        assertEquals(0, exited)
        compose.onNodeWithText(s(R.string.discard_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_discard)).performClick()
        assertEquals(1, exited)
    }

    @Test
    fun formRoundTripKeepsPolicyAndUsesNullForBlank() {
        val withPolicy = login.copy(
            content = (login.content as EntryContent.Login).copy(
                policy = io.github.ilwoong.passvault.data.model.PasswordPolicy(minLength = 12),
            ),
        )
        val form = EntryForm(EntryType.LOGIN, withPolicy)
        form.fields.first { it.key == FieldKey.URL }.state.setTextAndPlaceCursorAtEnd("")
        val draft = form.toDraft()
        val c = draft.content as EntryContent.Login
        assertEquals("id-1", draft.id)
        assertEquals(secret, c.password)
        assertEquals(null, c.url)
        assertEquals("M5 전까지 편집하지 않는 정책도 그대로 넘긴다", 12, c.policy?.minLength)
    }

    // --- UX-06 IME 학습 차단 ---

    @Test
    fun interceptorAddsNoPersonalizedLearningAfterComposeFillsEditorInfo() {
        val original = PlatformTextInputMethodRequest { info ->
            // Compose 처럼 imeOptions 를 '대입'한다 — 앞에서 더했다면 덮어쓰였을 것
            info.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
            dummyConnection()
        }
        val info = EditorInfo()
        withNoPersonalizedLearning(original).createInputConnection(info)

        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue("기존 플래그 유지", info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
        assertEquals(EditorInfo.IME_ACTION_DONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }

    private fun dummyConnection(): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java),
    ) { _, m, _ -> if (m.returnType == java.lang.Boolean.TYPE) false else null } as InputConnection
}
