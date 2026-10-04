package io.github.ilwoong.passvault.ui.onboarding

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.ui.common.BiometricEnroller
import io.github.ilwoong.passvault.ui.common.authenticateCipher
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UX-01 4 단계. 금고를 방금 만든 같은 흐름이라 재인증 없이 등록한다 (CRY-12 예외).
 */
@HiltViewModel
class WelcomeBiometricViewModel @Inject constructor(private val enroller: BiometricEnroller) : ViewModel() {
    fun newCipher() = enroller.newCipher()
    fun complete(authenticated: javax.crypto.Cipher?) = authenticated != null && enroller.complete(authenticated)
}

@Composable
fun WelcomeBiometricRoute(onDone: () -> Unit, vm: WelcomeBiometricViewModel = hiltViewModel()) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.bio_prompt_enroll_title)
    val negative = stringResource(R.string.action_cancel)
    WelcomeBiometricScreen(
        onEnable = {
            val cipher = vm.newCipher()
            if (cipher == null) {
                onDone()
            } else {
                scope.launch {
                    vm.complete(activity.authenticateCipher(title, negative, cipher))
                    onDone()
                }
            }
        },
        onLater = onDone,
    )
}

@Composable
fun WelcomeBiometricScreen(onEnable: () -> Unit, onLater: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.welcome_bio_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.welcome_bio_body))
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_enable)) }
            OutlinedButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_later)) }
        }
    }
}
