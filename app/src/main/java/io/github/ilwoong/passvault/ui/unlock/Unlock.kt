package io.github.ilwoong.passvault.ui.unlock

import io.github.ilwoong.passvault.ui.common.ScreenHeader
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.ilwoong.passvault.security.BiometricKeyStore
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.UnlockOutcome
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.common.authenticateCipher
import io.github.ilwoong.passvault.ui.copyToCharArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

enum class UnlockMessage { WRONG_PASSWORD, CANNOT_OPEN, BIOMETRIC_INVALIDATED }

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val session: SessionManager,
    private val keyStore: BiometricKeyStore,
) : ViewModel() {
    var message by mutableStateOf<UnlockMessage?>(null)
        private set
    var lockoutRemainingMs by mutableLongStateOf(0L)
        private set

    /** CRY-14: 래핑이 있고 기기에 Class 3 생체가 등록돼 있을 때만 제공한다. */
    var biometricOffered by mutableStateOf(session.isBiometricEnrolled() && keyStore.isAvailable())
        private set

    private var countdown: Job? = null

    init {
        startCountdown()
    }

    /** [password] 의 소유권을 넘겨받아 지운다. 성공하면 세션이 Unlocked 가 되어 화면이 바뀐다. */
    fun unlock(password: CharArray) {
        message = null
        viewModelScope.launch {
            val outcome = try {
                session.unlock(password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UnlockOutcome.CannotOpen
            } finally {
                password.zeroize()
            }
            when (outcome) {
                UnlockOutcome.Success -> Unit
                UnlockOutcome.WrongPassword -> {
                    // UX-02: 남은 시도 횟수는 보여주지 않는다
                    message = UnlockMessage.WRONG_PASSWORD
                    startCountdown()
                }
                is UnlockOutcome.LockedOut -> startCountdown()
                UnlockOutcome.CannotOpen -> message = UnlockMessage.CANNOT_OPEN
                UnlockOutcome.BiometricInvalidated -> Unit
            }
        }
    }

    /** CRY-13 2 단계. 키가 무효화됐으면 생체 해제를 끄고 안내한다 (SEC-11). */
    fun prepareBiometric(): Cipher? {
        val blob = session.biometricBlob() ?: return null.also { biometricOffered = false }
        return keyStore.decryptCipher(blob) ?: null.also { biometricInvalidated() }
    }

    /** CRY-13 3 단계 이후. */
    fun completeBiometric(authenticated: Cipher) {
        message = null
        viewModelScope.launch {
            val outcome = try {
                session.unlockWithBiometric { blob -> keyStore.unwrap(authenticated, blob) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UnlockOutcome.CannotOpen
            }
            when (outcome) {
                UnlockOutcome.BiometricInvalidated -> biometricInvalidated()
                UnlockOutcome.CannotOpen -> message = UnlockMessage.CANNOT_OPEN
                else -> Unit
            }
        }
    }

    private fun biometricInvalidated() {
        session.disableBiometric()
        keyStore.deleteKey()
        biometricOffered = false
        message = UnlockMessage.BIOMETRIC_INVALIDATED
    }

    /** LOCK-05: 판정은 저장된 실패 상태로 한다. 화면이 다시 열려도 이어진다. */
    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                val remaining = withContext(Dispatchers.IO) { session.lockoutRemainingMs() }
                lockoutRemainingMs = remaining
                if (remaining <= 0) break
                delay(minOf(remaining, 1_000))
            }
        }
    }
}

@Composable
fun UnlockRoute(unlocking: Boolean, onRestore: () -> Unit = {}, vm: UnlockViewModel = hiltViewModel()) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.bio_prompt_title)
    val negative = stringResource(R.string.bio_prompt_negative)
    val startBiometric: () -> Unit = {
        vm.prepareBiometric()?.let { cipher ->
            scope.launch { activity.authenticateCipher(title, negative, cipher)?.let(vm::completeBiometric) }
        }
    }
    // UX-02: 생체 해제가 설정돼 있으면 화면 진입 시 자동으로 띄운다
    LaunchedEffect(Unit) { if (vm.biometricOffered) startBiometric() }

    UnlockScreen(
        unlocking = unlocking,
        lockoutRemainingMs = vm.lockoutRemainingMs,
        message = vm.message,
        onUnlock = vm::unlock,
        biometricOffered = vm.biometricOffered,
        onBiometric = startBiometric,
        onRestore = onRestore,
    )
}

/** UX-02 */
@Composable
fun UnlockScreen(
    unlocking: Boolean,
    lockoutRemainingMs: Long,
    message: UnlockMessage?,
    onUnlock: (CharArray) -> Unit,
    biometricOffered: Boolean = false,
    onBiometric: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    // UX-00b: 저장 상태에 넣지 않는다
    val password = remember { TextFieldState() }
    val lockedOut = lockoutRemainingMs > 0
    val canSubmit = !unlocking && !lockedOut && password.text.isNotEmpty()
    val submit = {
        if (canSubmit) {
            onUnlock(password.text.copyToCharArray())
            password.clearText()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(stringResource(R.string.unlock_title))
            OutlinedSecureTextField(
                state = password,
                modifier = Modifier.fillMaxWidth(),
                enabled = !unlocking && !lockedOut,
                label = { Text(stringResource(R.string.label_master_password)) },
                onKeyboardAction = { submit() },
            )
            when (message) {
                UnlockMessage.WRONG_PASSWORD -> ErrorText(stringResource(R.string.error_wrong_password))
                UnlockMessage.CANNOT_OPEN -> ErrorText(stringResource(R.string.cannot_open_body))
                UnlockMessage.BIOMETRIC_INVALIDATED -> ErrorText(stringResource(R.string.bio_invalidated))
                null -> Unit
            }
            if (lockedOut) {
                ErrorText(stringResource(R.string.lockout_remaining, formatRemaining(lockoutRemainingMs)))
            }
            if (unlocking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.unlocking))
            }
            Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_unlock))
            }
            // 비밀번호 대기 중에도 생체 해제는 허용한다 (CRY-13)
            if (biometricOffered) {
                OutlinedButton(onClick = onBiometric, enabled = !unlocking, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_unlock_biometric))
                }
            }
            // 금고를 열 수 없는 사용자의 유일한 출구다 (BK-05)
            TextButton(onClick = onRestore, enabled = !unlocking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_restore_from_backup))
            }
        }
    }
}

@Composable
private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error)

/** 올림한 남은 초를 m:ss 로. */
internal fun formatRemaining(ms: Long): String {
    val totalSec = (ms + 999) / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
