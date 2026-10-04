package io.github.ilwoong.passvault.ui.unlock

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
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.UnlockOutcome
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.copyToCharArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class UnlockMessage { WRONG_PASSWORD, CANNOT_OPEN }

@HiltViewModel
class UnlockViewModel @Inject constructor(private val session: SessionManager) : ViewModel() {
    var message by mutableStateOf<UnlockMessage?>(null)
        private set
    var lockoutRemainingMs by mutableLongStateOf(0L)
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
            }
        }
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
fun UnlockRoute(unlocking: Boolean, vm: UnlockViewModel = hiltViewModel()) {
    UnlockScreen(
        unlocking = unlocking,
        lockoutRemainingMs = vm.lockoutRemainingMs,
        message = vm.message,
        onUnlock = vm::unlock,
    )
}

/** UX-02. 생체 해제는 M6, 백업 복구 진입점은 M8 에서 붙는다. */
@Composable
fun UnlockScreen(
    unlocking: Boolean,
    lockoutRemainingMs: Long,
    message: UnlockMessage?,
    onUnlock: (CharArray) -> Unit,
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
            Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
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
