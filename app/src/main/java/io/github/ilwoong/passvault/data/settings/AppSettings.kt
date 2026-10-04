package io.github.ilwoong.passvault.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** DM-02. 비밀이 아니다. */
data class LockSettings(
    val autoLockSeconds: Int = DEFAULT_AUTO_LOCK,
    val lockOnBackground: Boolean = true,
    val clipboardClearSeconds: Int = DEFAULT_CLIPBOARD,
) {
    companion object {
        val AUTO_LOCK_OPTIONS = listOf(15, 30, 60, 300)
        val CLIPBOARD_OPTIONS = listOf(0, 15, 30, 60)
        const val DEFAULT_AUTO_LOCK = 60
        const val DEFAULT_CLIPBOARD = 30
    }
}

/** DM-02 설정 저장소. 값 3개라 SharedPreferences 로 충분하다. 허용 목록 밖의 값은 기본값으로 읽는다. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LockSettings> = _state.asStateFlow()

    fun setAutoLockSeconds(value: Int) {
        require(value in LockSettings.AUTO_LOCK_OPTIONS)
        prefs.edit().putInt(K_AUTO_LOCK, value).apply()
        _state.value = read()
    }

    fun setLockOnBackground(value: Boolean) {
        prefs.edit().putBoolean(K_LOCK_ON_BACKGROUND, value).apply()
        _state.value = read()
    }

    fun setClipboardClearSeconds(value: Int) {
        require(value in LockSettings.CLIPBOARD_OPTIONS)
        prefs.edit().putInt(K_CLIPBOARD, value).apply()
        _state.value = read()
    }

    private fun read() = LockSettings(
        autoLockSeconds = prefs.getInt(K_AUTO_LOCK, LockSettings.DEFAULT_AUTO_LOCK)
            .takeIf { it in LockSettings.AUTO_LOCK_OPTIONS } ?: LockSettings.DEFAULT_AUTO_LOCK,
        lockOnBackground = prefs.getBoolean(K_LOCK_ON_BACKGROUND, true),
        clipboardClearSeconds = prefs.getInt(K_CLIPBOARD, LockSettings.DEFAULT_CLIPBOARD)
            .takeIf { it in LockSettings.CLIPBOARD_OPTIONS } ?: LockSettings.DEFAULT_CLIPBOARD,
    )

    private companion object {
        const val K_AUTO_LOCK = "autoLockSeconds"
        const val K_LOCK_ON_BACKGROUND = "lockOnBackground"
        const val K_CLIPBOARD = "clipboardClearSeconds"
    }
}
