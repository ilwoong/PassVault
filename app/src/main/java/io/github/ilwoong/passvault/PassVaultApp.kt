package io.github.ilwoong.passvault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import io.github.ilwoong.passvault.security.AutoLock
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PassVaultApp : Application() {

    @Inject
    lateinit var autoLock: AutoLock

    @Inject
    lateinit var session: SessionManager

    override fun onCreate() {
        super.onCreate()
        // LOCK-03: 해제 순간을 유휴 시작점으로 삼는다. Activity 수명에 묶으면 ON_START 마다 현재 상태가
        // 다시 전달되어 복귀·회전 때마다 유휴가 초기화된다. 그래서 프로세스 전역에서 한 번만 구독한다.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            session.state.collect { if (it == SessionState.Unlocked) autoLock.onUnlocked() }
        }
        // LOCK-03: 화면 꺼짐은 앱이 백그라운드여도 잠근다. 그래서 프로세스 전역에 둔다.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = autoLock.onScreenOff()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
