package io.github.ilwoong.passvault.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.UnlockOutcome
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.common.BiometricEnroller
import io.github.ilwoong.passvault.ui.common.authenticateCipher
import io.github.ilwoong.passvault.ui.copyToCharArray
import io.github.ilwoong.passvault.ui.unlock.UnlockMessage
import io.github.ilwoong.passvault.ui.unlock.formatRemaining
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

enum class SettingsEvent { BIO_ENABLED, BIO_DISABLED, BIO_FAILED }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: SessionManager,
    private val enroller: BiometricEnroller,
) : ViewModel() {

    /** CRY-14: 기기에 Class 3 생체가 없으면 항목 자체를 보여주지 않는다. */
    val biometricAvailable: Boolean = enroller.isAvailable

    var biometricEnrolled by mutableStateOf(session.isBiometricEnrolled())
        private set
    var reauthOpen by mutableStateOf(false)
        private set
    var reauthMessage by mutableStateOf<UnlockMessage?>(null)
        private set
    var reauthLockoutMs by mutableLongStateOf(0L)
        private set
    var event by mutableStateOf<SettingsEvent?>(null)
        private set

    fun onBiometricToggle(on: Boolean) {
        if (on) {
            // CRY-12: 해제된 상태여도 마스터 비밀번호를 다시 받는다 (T-02)
            reauthMessage = null
            reauthLockoutMs = 0
            reauthOpen = true
        } else {
            enroller.disable()
            biometricEnrolled = false
            event = SettingsEvent.BIO_DISABLED
        }
    }

    fun dismissReauth() {
        reauthOpen = false
    }

    /** UX-03 → CRY-12 2 단계. 확인되면 등록용 Cipher 를 [onVerified] 에 넘긴다. [password] 는 지운다. */
    fun reauthenticate(password: CharArray, onVerified: (Cipher) -> Unit) {
        viewModelScope.launch {
            val outcome = try {
                session.reauthenticate(password)
            } finally {
                password.zeroize()
            }
            when (outcome) {
                UnlockOutcome.Success -> {
                    reauthOpen = false
                    enroller.newCipher()?.let(onVerified) ?: run { event = SettingsEvent.BIO_FAILED }
                }
                UnlockOutcome.WrongPassword -> reauthMessage = UnlockMessage.WRONG_PASSWORD
                is UnlockOutcome.LockedOut -> {
                    reauthMessage = null
                    reauthLockoutMs = outcome.remainingMs
                }
                else -> reauthMessage = UnlockMessage.CANNOT_OPEN
            }
        }
    }

    /** CRY-12 4·5 단계. 프롬프트를 취소했으면 [authenticated] 가 null 이다. */
    fun completeEnroll(authenticated: Cipher?) {
        val ok = authenticated != null && enroller.complete(authenticated)
        biometricEnrolled = session.isBiometricEnrolled()
        event = if (ok) SettingsEvent.BIO_ENABLED else SettingsEvent.BIO_FAILED
    }

    fun consumeEvent() {
        event = null
    }
}

@Composable
fun SettingsRoute(onBack: () -> Unit, onChangePassword: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.bio_prompt_enroll_title)
    val negative = stringResource(R.string.action_cancel)
    SettingsScreen(
        biometricAvailable = vm.biometricAvailable,
        biometricEnrolled = vm.biometricEnrolled,
        reauthOpen = vm.reauthOpen,
        reauthMessage = vm.reauthMessage,
        reauthLockoutMs = vm.reauthLockoutMs,
        event = vm.event,
        onEventShown = vm::consumeEvent,
        onBack = onBack,
        onBiometricToggle = vm::onBiometricToggle,
        onReauthSubmit = { pw ->
            vm.reauthenticate(pw) { cipher ->
                scope.launch { vm.completeEnroll(activity.authenticateCipher(title, negative, cipher)) }
            }
        },
        onReauthDismiss = vm::dismissReauth,
        onChangePassword = onChangePassword,
    )
}

/** UX-08 ~ UX-12. M6 은 생체(UX-09)와 비밀번호 변경(UX-10). 나머지는 M7·M8·M9 에서 붙는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    biometricAvailable: Boolean,
    biometricEnrolled: Boolean,
    reauthOpen: Boolean,
    reauthMessage: UnlockMessage?,
    reauthLockoutMs: Long,
    event: SettingsEvent?,
    onEventShown: () -> Unit,
    onBack: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onReauthSubmit: (CharArray) -> Unit,
    onReauthDismiss: () -> Unit,
    onChangePassword: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val eventText = event?.let {
        stringResource(
            when (it) {
                SettingsEvent.BIO_ENABLED -> R.string.bio_enabled
                SettingsEvent.BIO_DISABLED -> R.string.bio_disabled
                SettingsEvent.BIO_FAILED -> R.string.bio_enroll_failed
            },
        )
    }
    LaunchedEffect(eventText) {
        if (eventText != null) {
            onEventShown()
            snackbar.showSnackbar(eventText)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (biometricAvailable) {
                ListItem(
                    modifier = Modifier.clickable { onBiometricToggle(!biometricEnrolled) },
                    headlineContent = { Text(stringResource(R.string.settings_biometric)) },
                    supportingContent = { Text(stringResource(R.string.settings_biometric_desc)) },
                    trailingContent = { Switch(checked = biometricEnrolled, onCheckedChange = onBiometricToggle) },
                )
                HorizontalDivider()
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onChangePassword),
                headlineContent = { Text(stringResource(R.string.settings_change_password)) },
                supportingContent = { Text(stringResource(R.string.settings_change_password_desc)) },
            )
            HorizontalDivider()
        }
    }

    if (reauthOpen) ReauthDialog(reauthMessage, reauthLockoutMs, onReauthSubmit, onReauthDismiss)
}

/** UX-03. 비밀번호는 저장되지 않는 상태에만 둔다 (UX-00b). */
@Composable
fun ReauthDialog(message: UnlockMessage?, lockoutMs: Long, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val password = remember { TextFieldState() }
    val lockedOut = lockoutMs > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reauth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reauth_body))
                OutlinedSecureTextField(
                    state = password,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !lockedOut,
                    label = { Text(stringResource(R.string.label_master_password)) },
                )
                if (message == UnlockMessage.WRONG_PASSWORD) {
                    Text(stringResource(R.string.error_wrong_password), color = MaterialTheme.colorScheme.error)
                }
                if (lockedOut) {
                    Text(stringResource(R.string.lockout_remaining, formatRemaining(lockoutMs)), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !lockedOut && password.text.isNotEmpty(),
                onClick = {
                    onSubmit(password.text.copyToCharArray())
                    password.clearText()
                },
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
