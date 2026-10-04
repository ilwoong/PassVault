package io.github.ilwoong.passvault.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.security.BiometricKeyStore
import io.github.ilwoong.passvault.ui.settings.ChangePasswordScreen
import io.github.ilwoong.passvault.ui.settings.ReauthDialog
import io.github.ilwoong.passvault.ui.settings.SettingsScreen
import io.github.ilwoong.passvault.ui.unlock.UnlockScreen
import io.github.ilwoong.passvault.ui.unlock.UnlockMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** CRY-14 노출 조건, UX-03 재인증, UX-10 비밀번호 변경 화면 */
@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun settings(available: Boolean, enrolled: Boolean = false, onToggle: (Boolean) -> Unit = {}) =
        compose.setContent {
            SettingsScreen(
                biometricAvailable = available, biometricEnrolled = enrolled,
                reauthOpen = false, reauthMessage = null, reauthLockoutMs = 0, event = null, onEventShown = {},
                onBack = {}, onBiometricToggle = onToggle, onReauthSubmit = {}, onReauthDismiss = {}, onChangePassword = {},
            )
        }

    @Test
    fun biometricToggleIsHiddenWhenDeviceHasNoStrongBiometric() {
        settings(available = false)
        compose.onAllNodesWithText(s(R.string.settings_biometric)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.settings_change_password)).assertIsDisplayed()
    }

    @Test
    fun biometricToggleShownWhenAvailable() {
        var toggled: Boolean? = null
        settings(available = true, onToggle = { toggled = it })
        compose.onNodeWithText(s(R.string.settings_biometric)).performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun unlockOffersBiometricOnlyWhenOffered() {
        compose.setContent { UnlockScreen(false, 0, null, {}, biometricOffered = false) }
        compose.onAllNodesWithText(s(R.string.action_unlock_biometric)).assertCountEquals(0)
    }

    @Test
    fun unlockShowsInvalidationMessage() {
        compose.setContent { UnlockScreen(false, 0, UnlockMessage.BIOMETRIC_INVALIDATED, {}, biometricOffered = false) }
        compose.onNodeWithText(s(R.string.bio_invalidated)).assertIsDisplayed()
    }

    @Test
    fun biometricStillOfferedDuringPasswordLockout() {
        var started = 0
        compose.setContent { UnlockScreen(false, 60_000, null, {}, biometricOffered = true, onBiometric = { started++ }) }
        compose.onNodeWithText(s(R.string.action_unlock)).assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.action_unlock_biometric)).assertIsEnabled().performClick()
        assertEquals(1, started)
    }

    @Test
    fun reauthDialogPassesPasswordAndShowsErrors() {
        var submitted: CharArray? = null
        compose.setContent { ReauthDialog(UnlockMessage.WRONG_PASSWORD, 0, { submitted = it.copyOf() }, {}) }
        compose.onNodeWithText(s(R.string.error_wrong_password)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.label_master_password)).performTextInput("my master pw")
        compose.onNodeWithText(s(R.string.action_confirm)).performClick()
        assertArrayEquals("my master pw".toCharArray(), submitted)
    }

    @Test
    fun reauthDialogDisabledDuringLockout() {
        compose.setContent { ReauthDialog(null, 30_000, {}, {}) }
        compose.onNodeWithText(s(R.string.lockout_remaining, "0:30")).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.label_master_password)).assertIsNotEnabled()
    }

    @Test
    fun changePasswordRequiresCurrentAndValidNewPassword() {
        var got: Pair<String, String>? = null
        compose.setContent {
            ChangePasswordScreen(false, null, 0, null, onChange = { a, b -> got = String(a) to String(b) }, onDone = {})
        }
        val change = compose.onNodeWithText(s(R.string.action_change))
        compose.onNodeWithText(s(R.string.label_new_password)).performTextInput("new-password-12")
        compose.onNodeWithText(s(R.string.label_confirm_new_password)).performTextInput("new-password-12")
        change.assertIsNotEnabled() // 현재 비밀번호 없음
        compose.onNodeWithText(s(R.string.label_current_password)).performTextInput("current-pw")
        change.assertIsEnabled().performClick()
        assertEquals("current-pw" to "new-password-12", got)
    }

    @Test
    fun changePasswordTellsToReEnableBiometric() {
        compose.setContent {
            ChangePasswordScreen(false, null, 0, io.github.ilwoong.passvault.ui.settings.ChangeResult.CHANGED_BIOMETRIC_OFF, { _, _ -> }, {})
        }
        compose.onNodeWithText(s(R.string.password_changed_bio)).assertIsDisplayed()
    }

    // --- CRY-14 실제 Keystore: 생체가 등록되지 않은 기기 ---

    @Test
    fun withoutEnrolledBiometricKeystoreRefusesAndNothingIsOffered() {
        val ks = BiometricKeyStore(context)
        assumeFalse("이 기기에는 생체가 등록돼 있다 — 등록 기기 시나리오는 E2E 에서 확인", ks.isAvailable())
        val created = runCatching { ks.newEncryptCipher() }
        assertEquals("생체 등록 없이는 인증 필수 키를 만들 수 없다", true, created.isFailure)
        assertNull(ks.decryptCipher(ByteArray(60)))
        ks.deleteKey()
    }
}
