package io.github.ilwoong.passvault.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.security.BiometricKeyStore
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.UnlockOutcome
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.copyToCharArray
import io.github.ilwoong.passvault.ui.onboarding.MIN_MASTER_PASSWORD_LENGTH
import io.github.ilwoong.passvault.ui.onboarding.PasswordStrength
import io.github.ilwoong.passvault.ui.onboarding.passwordStrength
import io.github.ilwoong.passvault.ui.unlock.UnlockMessage
import io.github.ilwoong.passvault.ui.unlock.formatRemaining
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ChangeResult { CHANGED, CHANGED_BIOMETRIC_OFF }

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val session: SessionManager,
    private val keyStore: BiometricKeyStore,
) : ViewModel() {
    var changing by mutableStateOf(false)
        private set
    var message by mutableStateOf<UnlockMessage?>(null)
        private set
    var lockoutMs by mutableLongStateOf(0L)
        private set
    var result by mutableStateOf<ChangeResult?>(null)
        private set

    /** CRY-15 / UX-10. 두 비밀번호의 소유권을 넘겨받아 지운다. */
    fun change(current: CharArray, new: CharArray) {
        changing = true
        message = null
        viewModelScope.launch {
            val hadBiometric = session.isBiometricEnrolled()
            val outcome = try {
                session.changeMasterPassword(current, new)
            } finally {
                current.zeroize()
                new.zeroize()
                changing = false
            }
            when (outcome) {
                UnlockOutcome.Success -> {
                    keyStore.deleteKey() // CRY-15 6 단계
                    result = if (hadBiometric) ChangeResult.CHANGED_BIOMETRIC_OFF else ChangeResult.CHANGED
                }
                UnlockOutcome.WrongPassword -> message = UnlockMessage.WRONG_PASSWORD
                is UnlockOutcome.LockedOut -> lockoutMs = outcome.remainingMs
                else -> message = UnlockMessage.CANNOT_OPEN
            }
        }
    }
}

@Composable
fun ChangePasswordRoute(onDone: () -> Unit, vm: ChangePasswordViewModel = hiltViewModel()) {
    ChangePasswordScreen(vm.changing, vm.message, vm.lockoutMs, vm.result, onChange = vm::change, onDone = onDone)
}

/** UX-10 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    changing: Boolean,
    message: UnlockMessage?,
    lockoutMs: Long,
    result: ChangeResult?,
    onChange: (CharArray, CharArray) -> Unit,
    onDone: () -> Unit,
) {
    // UX-00b: 저장 상태에 넣지 않는다
    val current = remember { TextFieldState() }
    val new = remember { TextFieldState() }
    val confirm = remember { TextFieldState() }
    val longEnough = new.text.length >= MIN_MASTER_PASSWORD_LENGTH
    val matches = new.text.contentEquals(confirm.text)
    val canSubmit = !changing && result == null && lockoutMs <= 0 && current.text.isNotEmpty() && longEnough && matches

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_change_password)) },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = !changing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (result != null) {
                Text(
                    stringResource(
                        if (result == ChangeResult.CHANGED_BIOMETRIC_OFF) R.string.password_changed_bio else R.string.password_changed,
                    ),
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_confirm)) }
                return@Column
            }
            OutlinedSecureTextField(
                state = current, modifier = Modifier.fillMaxWidth(), enabled = !changing,
                label = { Text(stringResource(R.string.label_current_password)) },
            )
            OutlinedSecureTextField(
                state = new, modifier = Modifier.fillMaxWidth(), enabled = !changing,
                label = { Text(stringResource(R.string.label_new_password)) },
                supportingText = {
                    Text(
                        if (longEnough) strengthText(passwordStrength(new.text))
                        else stringResource(R.string.password_too_short, MIN_MASTER_PASSWORD_LENGTH),
                    )
                },
            )
            OutlinedSecureTextField(
                state = confirm, modifier = Modifier.fillMaxWidth(), enabled = !changing,
                label = { Text(stringResource(R.string.label_confirm_new_password)) },
                isError = confirm.text.isNotEmpty() && !matches,
                supportingText = {
                    if (confirm.text.isNotEmpty() && !matches) Text(stringResource(R.string.password_mismatch))
                },
            )
            when {
                message == UnlockMessage.WRONG_PASSWORD -> Error(stringResource(R.string.error_wrong_password))
                message == UnlockMessage.CANNOT_OPEN -> Error(stringResource(R.string.cannot_open_body))
                lockoutMs > 0 -> Error(stringResource(R.string.lockout_remaining, formatRemaining(lockoutMs)))
            }
            if (changing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.changing_password))
            }
            Button(
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onChange(current.text.copyToCharArray(), new.text.copyToCharArray())
                    current.clearText()
                    new.clearText()
                    confirm.clearText()
                },
            ) { Text(stringResource(R.string.action_change)) }
        }
    }
}

@Composable
private fun Error(text: String) = Text(text, color = MaterialTheme.colorScheme.error)

@Composable
private fun strengthText(s: PasswordStrength) = stringResource(
    when (s) {
        PasswordStrength.WEAK -> R.string.strength_weak
        PasswordStrength.FAIR -> R.string.strength_fair
        PasswordStrength.STRONG -> R.string.strength_strong
        PasswordStrength.VERY_STRONG -> R.string.strength_very_strong
    },
)
