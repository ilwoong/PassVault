package io.github.ilwoong.passvault

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.ui.PassVaultRoot
import io.github.ilwoong.passvault.ui.theme.PassVaultTheme
import javax.inject.Inject

/** BiometricPrompt 가 FragmentActivity 를 요구한다 (CRY-13). */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // LOCK-06: 스크린샷·화면 녹화·최근앱 미리보기 차단.
        // Activity 가 하나이므로 여기 한 곳에서 끝난다. 디버그 빌드에서도 끄지 않는다.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        setContent {
            PassVaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PassVaultRoot(session)
                }
            }
        }
    }
}
