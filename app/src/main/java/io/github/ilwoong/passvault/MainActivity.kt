package io.github.ilwoong.passvault

import androidx.activity.enableEdgeToEdge
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.github.ilwoong.passvault.security.AutoLock
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.SessionState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.ilwoong.passvault.ui.PassVaultRoot
import io.github.ilwoong.passvault.ui.theme.PassVaultTheme
import javax.inject.Inject

/** BiometricPrompt 가 FragmentActivity 를 요구한다 (CRY-13). */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var session: SessionManager

    @Inject
    lateinit var autoLock: AutoLock

    override fun onCreate(savedInstanceState: Bundle?) {
        // LOCK-06: 스크린샷·화면 녹화·최근앱 미리보기 차단.
        // Activity 가 하나이므로 여기 한 곳에서 끝난다. 디버그 빌드에서도 끄지 않는다.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        // 시스템 바 뒤까지 그리고, 바의 아이콘 색을 밝은·어두운 테마에 맞춘다
        enableEdgeToEdge()
        setContent {
            PassVaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PassVaultRoot(session, onTextInput = autoLock::onInteraction)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // LOCK-03: 해제 순간을 유휴 시작점으로 삼는다
                launch { session.state.collect { if (it == SessionState.Unlocked) autoLock.onUnlocked() } }
                // 포그라운드 유휴 타이머
                while (true) {
                    delay(1_000)
                    autoLock.tick()
                }
            }
        }
    }

    /** LOCK-03: 터치와 하드웨어 키. 소프트 키보드 입력은 여기를 지나지 않아 입력 인터셉터가 따로 알린다. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLock.onInteraction()
    }

    override fun onStart() {
        super.onStart()
        autoLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // 화면 회전 등 구성 변경은 백그라운드 전환이 아니다
        if (!isChangingConfigurations) autoLock.onBackground()
    }
}
