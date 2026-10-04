package io.github.ilwoong.passvault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import io.github.ilwoong.passvault.security.AutoLock
import javax.inject.Inject

@HiltAndroidApp
class PassVaultApp : Application() {

    @Inject
    lateinit var autoLock: AutoLock

    override fun onCreate() {
        super.onCreate()
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
