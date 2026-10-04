package io.github.ilwoong.passvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UX-00, LOCK-04 4 단계: 최상위 분기(온보딩·해제·금고)마다 ViewModel 저장소를 따로 두고,
 * 분기가 바뀌면 이전 저장소를 비운다. 금고 분기의 ViewModel(복호화된 목록·상세)은 그보다 먼저,
 * 잠기는 순간 [BranchStores] 가 비운다.
 *
 * NavHost 가 컴포지션에서 빠지는 것만으로는 목적지 ViewModel 이 비워지지 않는다 (Activity 저장소에 남는다).
 * 금고 분기의 NavHost 는 이 저장소를 쓰므로 함께 비워진다.
 *
 * 저장소는 Activity 수준 ViewModel 에 두어 화면 회전에는 살아남는다.
 */
@Composable
fun BranchScope(key: String, content: @Composable () -> Unit) {
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val stores: BranchStores = viewModel(activityOwner)
    val store = stores.storeFor(key)
    val owner = remember(store) { BranchOwner(store, activityOwner as HasDefaultViewModelProviderFactory) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** 금고 분기의 키. 잠기면 이 분기의 ViewModel 을 비운다. */
const val VAULT_BRANCH = "vault"

@HiltViewModel
class BranchStores(sessionState: StateFlow<SessionState>) : ViewModel() {

    @Inject
    constructor(session: SessionManager) : this(session.state)

    private var currentKey: String? = null
    private var current: ViewModelStore? = null

    init {
        // LOCK-04 4 단계: 잠기는 즉시 비운다. 백그라운드에서는 컴포지션이 멈춰 있어 분기 전환은 복귀한 뒤에야
        // 일어난다 — 그때까지 두면 살아 있는 ViewModel 이 닫힌 DB 를 다시 읽으려다 죽는다.
        // Main.immediate 라서 메인 스레드의 lock() 안에서, DB 를 닫기 전에 실행된다.
        viewModelScope.launch {
            sessionState.collect { if (it != SessionState.Unlocked && currentKey == VAULT_BRANCH) current?.clear() }
        }
    }

    fun storeFor(key: String): ViewModelStore {
        if (key != currentKey) {
            current?.clear()
            current = ViewModelStore()
            currentKey = key
        }
        return checkNotNull(current)
    }

    override fun onCleared() {
        current?.clear()
    }
}

/** Hilt 의 ViewModel 팩토리를 쓰려면 HasDefaultViewModelProviderFactory 여야 한다. Activity 것을 빌린다. */
private class BranchOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = parent.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras: CreationExtras
        get() = parent.defaultViewModelCreationExtras
}
