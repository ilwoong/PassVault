package io.github.ilwoong.passvault.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.copyToCharArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { INTRO, PASSWORD }

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val session: SessionManager) : ViewModel() {
    var step by mutableStateOf(OnboardingStep.INTRO)
        private set
    var acknowledged by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun acknowledge(value: Boolean) {
        acknowledged = value
    }

    /** NFR-05: 복구 불가 경고를 확인해야만 다음으로 간다. */
    fun next() {
        if (acknowledged) step = OnboardingStep.PASSWORD
    }

    /** [password] 의 소유권을 넘겨받아 지운다. 성공하면 세션이 Unlocked 가 되어 화면이 바뀐다. */
    fun create(password: CharArray) {
        failed = false
        viewModelScope.launch {
            try {
                session.createVault(password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            } finally {
                password.zeroize()
            }
        }
    }
}

@Composable
fun OnboardingRoute(creating: Boolean, vm: OnboardingViewModel = hiltViewModel()) {
    OnboardingScreen(
        step = vm.step,
        acknowledged = vm.acknowledged,
        creating = creating,
        failed = vm.failed,
        onAcknowledgedChange = vm::acknowledge,
        onNext = vm::next,
        onCreate = vm::create,
    )
}

/** UX-01. 생체 등록(4 단계)은 M6, 백업 안내(5 단계)는 M8 에서 붙는다. */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    acknowledged: Boolean,
    creating: Boolean,
    failed: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onCreate: (CharArray) -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                creating -> Creating()
                step == OnboardingStep.INTRO -> Intro(acknowledged, onAcknowledgedChange, onNext)
                else -> PasswordForm(failed, onCreate)
            }
        }
    }
}

@Composable
private fun Intro(acknowledged: Boolean, onAcknowledgedChange: (Boolean) -> Unit, onNext: () -> Unit) {
    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.onboarding_intro))
    Text(stringResource(R.string.onboarding_warning), color = MaterialTheme.colorScheme.error)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = onAcknowledgedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = acknowledged, onCheckedChange = null)
        Text(stringResource(R.string.onboarding_acknowledge))
    }
    Button(onClick = onNext, enabled = acknowledged, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_next))
    }
}

@Composable
private fun PasswordForm(failed: Boolean, onCreate: (CharArray) -> Unit) {
    // UX-00b: 저장 상태(rememberSaveable)에 넣지 않는다
    val password = remember { TextFieldState() }
    val confirm = remember { TextFieldState() }
    val longEnough = password.text.length >= MIN_MASTER_PASSWORD_LENGTH
    val matches = password.text.contentEquals(confirm.text)

    Text(stringResource(R.string.onboarding_password_title), style = MaterialTheme.typography.headlineSmall)
    OutlinedSecureTextField(
        state = password,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.label_master_password)) },
        supportingText = {
            Text(
                if (longEnough) strengthText(passwordStrength(password.text))
                else stringResource(R.string.password_too_short, MIN_MASTER_PASSWORD_LENGTH),
            )
        },
    )
    OutlinedSecureTextField(
        state = confirm,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.label_confirm_password)) },
        isError = confirm.text.isNotEmpty() && !matches,
        supportingText = {
            if (confirm.text.isNotEmpty() && !matches) Text(stringResource(R.string.password_mismatch))
        },
    )
    if (failed) Text(stringResource(R.string.error_create_failed), color = MaterialTheme.colorScheme.error)
    Button(
        onClick = {
            onCreate(password.text.copyToCharArray())
            password.clearText()
            confirm.clearText()
        },
        enabled = longEnough && matches,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_create_vault))
    }
}

@Composable
private fun strengthText(s: PasswordStrength) = stringResource(
    when (s) {
        PasswordStrength.WEAK -> R.string.strength_weak
        PasswordStrength.FAIR -> R.string.strength_fair
        PasswordStrength.STRONG -> R.string.strength_strong
        PasswordStrength.VERY_STRONG -> R.string.strength_very_strong
    },
)

/** UX-01 3 단계. 취소할 수 없다 — 중간에 끊으면 메타가 깨질 위험이 있다. */
@Composable
private fun ColumnScope.Creating() {
    Spacer(Modifier.padding(top = 48.dp))
    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
    Text(stringResource(R.string.creating_vault), modifier = Modifier.align(Alignment.CenterHorizontally))
}
