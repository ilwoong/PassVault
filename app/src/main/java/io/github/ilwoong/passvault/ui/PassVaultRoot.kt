package io.github.ilwoong.passvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
 * UX-06: 앱의 모든 입력란에 IME 개인화 학습 차단을 건다.
 */
@Composable
fun PassVaultRoot(session: SessionManager) = NoPersonalizedLearning {
    val state by session.state.collectAsStateWithLifecycle()
    val branch = when (state) {
        SessionState.NoVault, SessionState.Creating -> "onboarding"
        SessionState.Corrupt -> "corrupt"
        SessionState.Locked, SessionState.Unlocking -> "unlock"
        SessionState.Unlocked -> "vault"
    }
    BranchScope(branch) {
        when (state) {
            SessionState.NoVault, SessionState.Creating -> OnboardingRoute(creating = state == SessionState.Creating)
            SessionState.Corrupt -> CannotOpenScreen()
            SessionState.Locked, SessionState.Unlocking -> UnlockRoute(unlocking = state == SessionState.Unlocking)
            SessionState.Unlocked -> VaultNavHost()
        }
    }
}

/** ARC-06. 백업 복구 진입점은 M8 에서 붙는다. 아무것도 지우거나 다시 만들지 않는다. */
@Composable
private fun CannotOpenScreen() {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.cannot_open_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.cannot_open_body))
        }
    }
}

/** [String] 을 거치지 않고 입력 버퍼를 복사한다. 반환값은 받는 쪽이 지운다. */
fun CharSequence.copyToCharArray(): CharArray = CharArray(length) { this[it] }
