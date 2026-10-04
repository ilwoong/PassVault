package io.github.ilwoong.passvault.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.ui.onboarding.OnboardingScreen
import io.github.ilwoong.passvault.ui.onboarding.OnboardingStep
import io.github.ilwoong.passvault.ui.unlock.UnlockMessage
import io.github.ilwoong.passvault.ui.unlock.UnlockScreen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UX-01, UX-02 화면 규칙. 상태 없는 화면을 직접 띄워 확인한다. */
@RunWith(AndroidJUnit4::class)
class LockScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun s(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    // --- UX-01 ---

    @Test
    fun cannotProceedWithoutAcknowledgingUnrecoverableWarning() {
        var acknowledged = false
        var nextClicked = 0
        compose.setContent {
            OnboardingScreen(
                step = OnboardingStep.INTRO, acknowledged = acknowledged, creating = false, failed = false,
                onAcknowledgedChange = { acknowledged = it }, onNext = { nextClicked++ }, onCreate = {},
            )
        }
        compose.onNodeWithText(s(R.string.onboarding_warning)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_next)).assertIsNotEnabled()
        assertEquals("NFR-05", 0, nextClicked)
    }

    @Test
    fun nextEnabledOnceAcknowledged() {
        compose.setContent {
            OnboardingScreen(
                step = OnboardingStep.INTRO, acknowledged = true, creating = false, failed = false,
                onAcknowledgedChange = {}, onNext = {}, onCreate = {},
            )
        }
        compose.onNodeWithText(s(R.string.action_next)).assertIsEnabled()
    }

    @Test
    fun createRequiresMinLengthAndMatchingConfirmation() {
        var created: CharArray? = null
        compose.setContent {
            OnboardingScreen(
                step = OnboardingStep.PASSWORD, acknowledged = true, creating = false, failed = false,
                onAcknowledgedChange = {}, onNext = {}, onCreate = { created = it.copyOf() },
            )
        }
        val pwField = compose.onNodeWithText(s(R.string.label_master_password))
        val confirmField = compose.onNodeWithText(s(R.string.label_confirm_password))
        val create = compose.onNodeWithText(s(R.string.action_create_vault))

        pwField.performTextInput("short-11ch")       // 10자
        confirmField.performTextInput("short-11ch")
        create.assertIsNotEnabled()

        pwField.performTextInput("XY")                 // 12자, 확인란과 불일치
        create.assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.password_mismatch)).assertIsDisplayed()

        confirmField.performTextInput("XY")            // 일치
        create.assertIsEnabled().performClick()
        assertArrayEquals("short-11chXY".toCharArray(), created)
    }

    @Test
    fun creatingShowsProgressInsteadOfForm() {
        compose.setContent {
            OnboardingScreen(
                step = OnboardingStep.PASSWORD, acknowledged = true, creating = true, failed = false,
                onAcknowledgedChange = {}, onNext = {}, onCreate = {},
            )
        }
        compose.onNodeWithText(s(R.string.creating_vault)).assertIsDisplayed()
    }

    // --- UX-02 ---

    @Test
    fun unlockPassesTypedPasswordAndClearsField() {
        var submitted: CharArray? = null
        compose.setContent {
            UnlockScreen(unlocking = false, lockoutRemainingMs = 0, message = null, onUnlock = { submitted = it.copyOf() })
        }
        compose.onNodeWithText(s(R.string.label_master_password)).performTextInput("my master pw")
        compose.onNodeWithText(s(R.string.action_unlock)).performClick()

        assertArrayEquals("my master pw".toCharArray(), submitted)
        compose.onNodeWithText(s(R.string.action_unlock)).assertIsNotEnabled() // 비워져서 다시 제출 불가
    }

    @Test
    fun lockoutDisablesInputAndShowsCountdown() {
        var submitted: CharArray? = null
        compose.setContent {
            UnlockScreen(unlocking = false, lockoutRemainingMs = 90_000, message = null, onUnlock = { submitted = it })
        }
        compose.onNodeWithText(s(R.string.lockout_remaining, "1:30")).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.label_master_password)).assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.action_unlock)).assertIsNotEnabled()
        assertNull(submitted)
    }

    @Test
    fun wrongPasswordShowsOnlyGenericMessage() {
        compose.setContent {
            UnlockScreen(unlocking = false, lockoutRemainingMs = 0, message = UnlockMessage.WRONG_PASSWORD, onUnlock = {})
        }
        compose.onNodeWithText(s(R.string.error_wrong_password)).assertIsDisplayed()
    }

    @Test
    fun unlockingDisablesInput() {
        compose.setContent {
            UnlockScreen(unlocking = true, lockoutRemainingMs = 0, message = null, onUnlock = {})
        }
        compose.onNodeWithText(s(R.string.unlocking)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.label_master_password)).assertIsNotEnabled()
    }
}
