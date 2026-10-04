package io.github.ilwoong.passvault.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.ui.settings.AboutScreen
import io.github.ilwoong.passvault.ui.settings.OSS_NOTICES
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UX-12 */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun showsVersionAndLocalOnlyNotice() {
        compose.setContent { AboutScreen("1.2.3", onBack = {}) }
        compose.onNodeWithText(s(R.string.about_version, "1.2.3")).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.about_local_only)).assertIsDisplayed()
    }

    @Test
    fun sqlcipherAttributionIsReachable() {
        // SQLCipher Community 라이선스는 앱 안에서 저작권 고지를 볼 수 있어야 한다
        compose.setContent { AboutScreen("1.0.0", onBack = {}) }
        compose.onNodeWithText("SQLCipher for Android").performScrollTo().performClick()
        compose.onNodeWithText("Neither the name of the ZETETIC LLC", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun longLicenseTextIsShownInDialog() {
        compose.setContent { AboutScreen("1.0.0", onBack = {}) }
        compose.onNodeWithText("Dagger, Hilt").performScrollTo().performClick()
        compose.onNodeWithText("TERMS AND CONDITIONS FOR USE", substring = true).assertExists()
    }

    @Test
    fun everyLicenseTextResourceIsReadable() {
        for (notice in OSS_NOTICES) {
            val id = notice.text ?: continue
            val text = context.resources.openRawResource(id).bufferedReader().use { it.readText() }
            assertTrue(notice.name, text.length > 500)
        }
    }
}
