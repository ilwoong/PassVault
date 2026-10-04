package io.github.ilwoong.passvault.ui

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntrySummary
import io.github.ilwoong.passvault.data.model.EntryType
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.ui.detail.EntryDetailScreen
import io.github.ilwoong.passvault.ui.edit.EntryEditScreen
import io.github.ilwoong.passvault.ui.edit.EntryForm
import io.github.ilwoong.passvault.ui.edit.FieldKey
import io.github.ilwoong.passvault.ui.list.EntryListScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val DAY = 24L * 60 * 60 * 1000
private const val NOW = 1_000 * DAY

/** M5 검증: UX-05 정책 표시, UX-07 실시간 검사, UX-04 배지 */
@RunWith(AndroidJUnit4::class)
class PolicyScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun s(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun login(password: String, policy: PasswordPolicy?, changedAt: Long? = NOW) = Entry(
        "id", "Site", false, 0, 0, changedAt, EntryContent.Login(username = "u", password = password, policy = policy),
    )

    private fun showDetail(e: Entry) = compose.setContent {
        EntryDetailScreen(e, onBack = {}, onEdit = {}, onDelete = {}, onCopy = {}, nowEpochMs = NOW)
    }

    @Test
    fun noPolicyShowsRecordPromptAndNoVerdict() {
        showDetail(login("pw", null))
        compose.onNodeWithText(s(R.string.policy_record_prompt)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.policy_satisfied)).assertCountEquals(0)
    }

    @Test
    fun rawNoteOnlyIsShownButNeverClaimsSatisfied() {
        showDetail(login("pw", PasswordPolicy(rawNote = "특수문자는 !@# 만 허용")))
        compose.onNodeWithText("특수문자는 !@# 만 허용").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.policy_satisfied)).assertCountEquals(0)
    }

    @Test
    fun allowedOnlyRulesAreSummarizedWithoutVerdict() {
        showDetail(login("pw", PasswordPolicy(upperRule = CharClassRule.ALLOWED)))
        compose.onNodeWithText("• " + s(R.string.summary_rule, s(R.string.policy_upper), s(R.string.rule_allowed)))
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.policy_satisfied)).assertCountEquals(0)
    }

    @Test
    fun eachViolationShowsItsOwnMessage() {
        val policy = PasswordPolicy(
            minLength = 20, upperRule = CharClassRule.REQUIRED, digitRule = CharClassRule.FORBIDDEN,
            disallowSpace = true, maxRepeatRun = 1, allowedSymbols = "!",
        )
        showDetail(login("aa b1$", policy))
        for (msg in listOf(
            R.string.v_too_short, R.string.v_missing_upper, R.string.v_forbidden_digit,
            R.string.v_contains_space, R.string.v_repeat_run, R.string.v_disallowed_symbol,
        )) {
            compose.onNodeWithText("• " + s(msg)).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithText(s(R.string.policy_satisfied)).assertCountEquals(0)
    }

    @Test
    fun satisfiedPolicySaysSo() {
        showDetail(login("Abcd1234", PasswordPolicy(minLength = 8, upperRule = CharClassRule.REQUIRED)))
        compose.onNodeWithText(s(R.string.policy_satisfied)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rotationWarningAndElapsedDays() {
        showDetail(login("pw", PasswordPolicy(rotationDays = 90), changedAt = NOW - 100 * DAY))
        compose.onNodeWithText(s(R.string.policy_days_since_change, 100)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("• " + s(R.string.v_rotation_due)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun liveCheckReactsWhileTypingPolicy() {
        val form = EntryForm(EntryType.LOGIN, login("short", null))
        compose.setContent { EntryEditScreen(form, saving = false, failed = false, onSave = {}, onExit = {}, nowEpochMs = NOW) }

        compose.onNodeWithText(s(R.string.policy_section_title)).performScrollTo().performClick()
        form.policy!!.minLength.setTextAndPlaceCursorAtEnd("10")
        compose.onNodeWithText("• " + s(R.string.v_too_short)).performScrollTo().assertIsDisplayed()

        form.fields.first { it.key == FieldKey.PASSWORD }.state.setTextAndPlaceCursorAtEnd("long-enough-password")
        compose.onNodeWithText(s(R.string.policy_satisfied)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun listShowsBadgesFromCacheFlags() {
        val rows = listOf(
            EntrySummary("1", EntryType.LOGIN, "A", "u", false, hasPolicyViolation = true, hasRotationDue = false),
            EntrySummary("2", EntryType.LOGIN, "B", null, false, hasPolicyViolation = false, hasRotationDue = true),
            EntrySummary("3", EntryType.LOGIN, "C", null, false, hasPolicyViolation = false, hasRotationDue = false),
        )
        compose.setContent { EntryListScreen(rows, "", null, {}, {}, {}, { _, _ -> }, {}, {}) }
        compose.onAllNodesWithText(s(R.string.badge_violation)).assertCountEquals(1)
        compose.onAllNodesWithText(s(R.string.badge_rotation)).assertCountEquals(1)
    }
}
