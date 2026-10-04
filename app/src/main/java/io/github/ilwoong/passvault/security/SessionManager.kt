package io.github.ilwoong.passvault.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** LOCK-01. Unlocked 외에는 모두 VK 가 없고 DB 가 닫혀 있다. */
sealed interface SessionState {
    data object NoVault : SessionState
    data object Corrupt : SessionState
    data object Locked : SessionState
    data object Creating : SessionState
    data object Unlocking : SessionState
    data object Unlocked : SessionState
}

/** UI 로 돌려주는 해제 결과. VK 를 담지 않는다. */
sealed interface UnlockOutcome {
    data object Success : UnlockOutcome
    data object WrongPassword : UnlockOutcome
    data class LockedOut(val remainingMs: Long) : UnlockOutcome

    /** vault_meta 손상 또는 DB 를 열 수 없음. 화면 문구가 같다 (ARC-06). */
    data object CannotOpen : UnlockOutcome
}

/** 해제 동안만 열려 있는 자원 (ARC-03). data 계층의 DB 홀더가 구현한다. */
interface SessionResource {
    /** [vaultKey] 는 빌려준 것이다. 보관하지 말고 필요한 형태로만 바꾼다 (LOCK-02). 실패하면 던진다. */
    fun onUnlocked(vaultKey: ByteArray)

    fun onLocked()
}

/** LOCK-01 ~ LOCK-05. 잠금 여부의 유일한 원천이며 VK 를 보유하는 유일한 곳이다. */
class SessionManager(
    private val keyManager: VaultKeyManager,
    private val resource: SessionResource,
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(stateFromDisk())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** create·unlock 이 겹치지 않게 한다. */
    private val attempt = Mutex()

    /** VK 접근(withVaultKey)과 잠금이 겹치지 않게 한다. */
    private val keyLock = Any()
    private var vaultKey: ByteArray? = null

    /** CRY-10. 성공하면 Unlocked. [password] 는 호출자가 지운다. */
    suspend fun createVault(password: CharArray): Unit = attempt.withLock {
        check(_state.value == SessionState.NoVault) { "금고가 이미 있다" }
        _state.value = SessionState.Creating
        try {
            withContext(kdfDispatcher) { openSession(keyManager.createVault(password)) }
        } finally {
            if (_state.value != SessionState.Unlocked) _state.value = stateFromDisk()
        }
    }

    /** CRY-11, LOCK-05. [password] 는 호출자가 지운다. */
    suspend fun unlock(password: CharArray): UnlockOutcome = attempt.withLock {
        when (_state.value) {
            SessionState.Unlocked -> return UnlockOutcome.Success
            SessionState.Locked -> Unit
            else -> return UnlockOutcome.CannotOpen
        }
        _state.value = SessionState.Unlocking
        try {
            withContext(kdfDispatcher) {
                when (val r = keyManager.unlock(password)) {
                    is UnlockResult.Success -> try {
                        openSession(r.vaultKey)
                        UnlockOutcome.Success
                    } catch (e: Exception) {
                        // DB 를 열 수 없다. 아무것도 지우거나 다시 만들지 않는다 (ARC-06)
                        UnlockOutcome.CannotOpen
                    }
                    UnlockResult.WrongPassword -> UnlockOutcome.WrongPassword
                    is UnlockResult.LockedOut -> UnlockOutcome.LockedOut(r.remainingMs)
                    UnlockResult.NoVault, UnlockResult.Corrupt -> UnlockOutcome.CannotOpen
                }
            }
        } finally {
            if (_state.value != SessionState.Unlocked) _state.value = stateFromDisk()
        }
    }

    /** UI 카운트다운용 (UX-02). */
    fun lockoutRemainingMs(): Long = keyManager.lockoutRemainingMs()

    /**
     * LOCK-04. 순서: 상태 전환 → (UI 가 분기째 버림, UX-00) → DB close → VK 제로화.
     * 클립보드 삭제(6 단계)는 M7 에서 붙는다.
     */
    fun lock() {
        synchronized(keyLock) {
            if (_state.value != SessionState.Unlocked) return
            _state.value = SessionState.Locked
            try {
                resource.onLocked()
            } finally {
                vaultKey?.zeroize()
                vaultKey = null
            }
        }
    }

    /** LOCK-02. 잠겨 있으면 null. [block] 밖으로 키를 내보내지 않는다. */
    fun <R> withVaultKey(block: (ByteArray) -> R): R? = synchronized(keyLock) {
        vaultKey?.let(block)
    }

    private fun openSession(key: ByteArray) {
        try {
            resource.onUnlocked(key)
        } catch (e: Throwable) {
            key.zeroize()
            throw e
        }
        synchronized(keyLock) {
            vaultKey = key
            _state.value = SessionState.Unlocked
        }
    }

    private fun stateFromDisk(): SessionState = when (keyManager.vaultStatus()) {
        MetaReadResult.Absent -> SessionState.NoVault
        MetaReadResult.Corrupt -> SessionState.Corrupt
        is MetaReadResult.Present -> SessionState.Locked
    }
}
