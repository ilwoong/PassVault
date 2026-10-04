package io.github.ilwoong.passvault.ui

import io.github.ilwoong.passvault.ui.common.ScreenHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.ilwoong.passvault.ui.backup.RestoreRoute
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.SessionState
import io.github.ilwoong.passvault.ui.common.NoPersonalizedLearning
import io.github.ilwoong.passvault.ui.onboarding.OnboardingRoute
import io.github.ilwoong.passvault.ui.unlock.UnlockRoute

/**
 * UX-00: 세션 상태로 최상위 분기를 고른다. 분기가 바뀌면 이전 분기의 컴포지션·ViewModel 이 함께 사라진다.
 * UX-06: 앱의 모든 입력란에 IME 개인화 학습 차단을 건다. 키보드 입력은 [onTextInput] 으로 알린다 (LOCK-03).
 */
@Composable
fun PassVaultRoot(session: SessionManager, onTextInput: () -> Unit = {}) = NoPersonalizedLearning(onTextInput) {
    val state by session.state.collectAsStateWithLifecycle()
    val branch = when (state) {
        SessionState.NoVault, SessionState.Creating -> "onboarding"
        // 손상 화면에서 시작한 복구(BK-05)가 상태 변화로 취소되지 않게 같은 분기로 둔다
        SessionState.Corrupt, SessionState.Locked, SessionState.Unlocking -> "unlock"
        SessionState.Unlocked -> VAULT_BRANCH
    }
    BranchScope(branch) {
        when (state) {
            SessionState.NoVault, SessionState.Creating -> OnboardingRoute(creating = state == SessionState.Creating)
            SessionState.Corrupt, SessionState.Locked, SessionState.Unlocking -> LockedBranch(state)
            SessionState.Unlocked -> VaultNavHost()
        }
    }
}

@Composable
private fun LockedBranch(state: SessionState) {
    // 비밀이 아닌 화면 선택값이다
    var restoring by rememberSaveable { mutableStateOf(false) }
    when {
        restoring -> RestoreRoute(onCancel = { restoring = false })
        state == SessionState.Corrupt -> CannotOpenScreen(onRestore = { restoring = true })
        else -> UnlockRoute(unlocking = state == SessionState.Unlocking, onRestore = { restoring = true })
    }
}

/** ARC-06. 아무것도 지우거나 다시 만들지 않는다. 복구는 사용자가 고른 백업 파일로만 (BK-05). */
@Composable
private fun CannotOpenScreen(onRestore: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader(stringResource(R.string.cannot_open_title), stringResource(R.string.cannot_open_body))
            OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_restore_from_backup))
            }
        }
    }
}

/** [String] 을 거치지 않고 입력 버퍼를 복사한다. 반환값은 받는 쪽이 지운다. */
fun CharSequence.copyToCharArray(): CharArray = CharArray(length) { this[it] }
