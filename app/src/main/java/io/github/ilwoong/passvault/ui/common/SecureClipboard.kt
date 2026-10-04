package io.github.ilwoong.passvault.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** 지금 클립보드에 있는 클립을 읽은 결과. */
sealed interface CurrentClip {
    /** Android 10+ 에서 포커스가 없는 앱은 클립보드를 읽을 수 없다. */
    data object Unreadable : CurrentClip

    /** 읽었다. [nonce] 는 우리가 넣은 표식이며, 다른 앱의 클립이면 null 이다. */
    data class Readable(val nonce: String?) : CurrentClip
}

/** 시스템 클립보드 접근. 판정 로직을 플랫폼 없이 검증하려고 분리한다. */
interface ClipboardAccess {
    /** 민감 표시와 표식을 달아 넣는다. */
    fun set(value: String, nonce: String)
    fun current(): CurrentClip
    fun clear()
}

class AndroidClipboardAccess(context: Context) : ClipboardAccess {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    override fun set(value: String, nonce: String) {
        val clip = ClipData.newPlainText(SecureClipboard.LABEL, value)
        clip.description.extras = PersistableBundle().apply {
            // API 33 의 ClipDescription.EXTRA_IS_SENSITIVE. 그 이전 버전에서는 일부 제조사만 존중한다.
            putBoolean(SecureClipboard.EXTRA_IS_SENSITIVE, true)
            putString(SecureClipboard.EXTRA_NONCE, nonce)
        }
        manager.setPrimaryClip(clip)
    }

    override fun current(): CurrentClip {
        val description = manager.primaryClipDescription ?: return CurrentClip.Unreadable
        return CurrentClip.Readable(description.extras?.getString(SecureClipboard.EXTRA_NONCE))
    }

    override fun clear() = manager.clearPrimaryClip()
}

/**
 * UX-05, LOCK-07: 금고에서 나가는 모든 값을 민감 표시와 함께 복사하고, 일정 시간 뒤 지운다.
 *
 * SEC-12 예외: ClipData 는 CharSequence 를 요구한다.
 */
class SecureClipboard(
    private val access: ClipboardAccess,
    private val clearAfterSeconds: () -> Int,
    private val scope: CoroutineScope,
) {
    /** 마지막으로 넣은 클립의 표식. null 이면 지울 것이 없다. */
    @Volatile
    private var nonce: String? = null
    private var clearJob: Job? = null

    /** 복사하고 자동 삭제까지의 초를 돌려준다 (0 = 자동 삭제 안 함). */
    fun copy(value: String): Int {
        val mark = UUID.randomUUID().toString()
        access.set(value, mark)
        nonce = mark

        val seconds = clearAfterSeconds()
        clearJob?.cancel()
        if (seconds > 0) {
            clearJob = scope.launch {
                delay(seconds * 1_000L)
                clearIfOurs()
            }
        }
        return seconds
    }

    /**
     * LOCK-07. 지금 클립이 우리가 넣은 것이면 지운다. 다른 앱이 그 뒤에 복사했으면 두고 간다.
     * 읽을 수 없으면(Android 10+ 백그라운드) 확인할 수 없다 — 그때는 지운다.
     */
    fun clearIfOurs() {
        val mark = nonce ?: return
        val ours = when (val c = access.current()) {
            CurrentClip.Unreadable -> true
            is CurrentClip.Readable -> c.nonce == mark
        }
        if (ours) access.clear()
        nonce = null
        clearJob?.cancel()
    }

    companion object {
        const val LABEL = "PassVault"
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        internal const val EXTRA_NONCE = "io.github.ilwoong.passvault.CLIP_NONCE"
    }
}
