package io.github.ilwoong.passvault.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UX-05, LOCK-07: 금고에서 나가는 모든 값을 민감 표시와 함께 복사한다.
 * 자동 삭제는 M7 에서 붙는다.
 *
 * SEC-12 예외: ClipData 는 CharSequence 를 요구한다.
 */
@Singleton
class SecureClipboard @Inject constructor(@ApplicationContext context: Context) {

    private val manager = context.getSystemService(ClipboardManager::class.java)

    fun copy(value: String) {
        val clip = ClipData.newPlainText(LABEL, value)
        // API 33 의 ClipDescription.EXTRA_IS_SENSITIVE. 그 이전 버전에서는 일부 제조사만 존중한다.
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        manager.setPrimaryClip(clip)
    }

    companion object {
        const val LABEL = "PassVault"
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
