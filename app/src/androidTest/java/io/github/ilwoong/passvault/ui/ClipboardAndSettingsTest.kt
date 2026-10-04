package io.github.ilwoong.passvault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.data.settings.AppSettings
import io.github.ilwoong.passvault.data.settings.LockSettings
import io.github.ilwoong.passvault.ui.common.AndroidClipboardAccess
import io.github.ilwoong.passvault.ui.common.CurrentClip
import io.github.ilwoong.passvault.ui.common.SecureClipboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LOCK-07 플랫폼 확인, DM-02 설정.
 *
 * 판정 로직은 JVM(SecureClipboardTest)에서 검증한다. 에뮬레이터는 호스트와 클립보드를 동기화하며
 * 클립을 곧바로 "host clipboard" 로 다시 써넣으므로, 플랫폼 확인은 같은 메인 작업 안에서 끝낸다.
 * 클립보드는 포커스를 가진 앱만 읽을 수 있어 Compose 테스트 Activity 를 띄워 둔다.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardAndSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(ClipboardManager::class.java)

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Before
    fun setUp() {
        compose.setContent { Text("clipboard focus") }
        compose.waitForIdle()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        onMain { manager.clearPrimaryClip() }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun platformKeepsSensitiveFlagAndOurMark() {
        // 에뮬레이터의 호스트 동기화가 먼저 끼어들면 그 시도는 판정할 수 없다. 끼어들기 전에 읽힌 시도로 확인한다.
        var verified = false
        repeat(10) { attempt ->
            if (verified) return@repeat
            onMain {
                val access = AndroidClipboardAccess(context)
                access.set("S3cret", "mark-$attempt")
                val d = manager.primaryClipDescription!!
                if (d.label == SecureClipboard.LABEL) {
                    // 한 번 읽은 설명에서 모두 확인한다 — 두 번 읽으면 그 사이에 에코가 끼어들 수 있다
                    assertTrue(d.extras!!.getBoolean(SecureClipboard.EXTRA_IS_SENSITIVE))
                    assertEquals("mark-$attempt", d.extras!!.getString(SecureClipboard.EXTRA_NONCE))
                    verified = true
                }
            }
        }
        assumeTrue("에뮬레이터 클립보드 동기화가 매번 먼저 덮어써 확인할 수 없었다", verified)
    }

    @Test
    fun anotherClipHasNoMarkOfOurs() {
        onMain {
            manager.setPrimaryClip(ClipData.newPlainText("other", "someone else"))
            assertEquals(CurrentClip.Readable(null), AndroidClipboardAccess(context).current())
        }
    }

    @Test
    fun clearRemovesTheClip() {
        onMain {
            val access = AndroidClipboardAccess(context)
            access.set("S3cret", "mark-2")
            access.clear()
            val text = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
            assertTrue("비어 있어야 한다: $text", text.isNullOrEmpty())
        }
    }

    // --- DM-02 ---

    @Test
    fun settingsDefaultsAndPersistence() {
        val s = AppSettings(context)
        assertEquals(LockSettings(60, true, 30), s.state.value)
        s.setAutoLockSeconds(15)
        s.setLockOnBackground(false)
        s.setClipboardClearSeconds(0)
        assertEquals(LockSettings(15, false, 0), AppSettings(context).state.value)
    }

    @Test
    fun outOfRangeStoredValuesReadAsDefaults() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt("autoLockSeconds", 0).putInt("clipboardClearSeconds", 999).commit()
        assertEquals(LockSettings(60, true, 30), AppSettings(context).state.value)
        assertThrows(IllegalArgumentException::class.java) { AppSettings(context).setAutoLockSeconds(0) }
        assertFalse(LockSettings.AUTO_LOCK_OPTIONS.contains(0))
    }
}
