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

    /** CRY-13 4·5 단계: 생체 키가 무효화됐거나 래핑이 손상됐다. 생체 해제는 이미 꺼졌다. */
    data object BiometricInvalidated : UnlockOutcome
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

    /** UX-01 4 단계: 금고를 방금 만들었는지. 한 번 읽으면 사라진다. */
    @Volatile
    private var justCreated = false

    /** CRY-10. 성공하면 Unlocked. [password] 는 호출자가 지운다. */
    suspend fun createVault(password: CharArray): Unit = attempt.withLock {
        check(_state.value == SessionState.NoVault) { "금고가 이미 있다" }
        _state.value = SessionState.Creating
        try {
            withContext(kdfDispatcher) { openSession(keyManager.createVault(password)) }
            justCreated = true
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

    /** UX-01 4 단계를 보여줄지. 한 번만 true 다. */
    fun consumeJustCreated(): Boolean = justCreated.also { justCreated = false }

    // --- CRY-12 ~ CRY-15 ---

    /** 생체 해제가 설정돼 있는지. 설정값을 따로 두지 않고 vault_meta 에서 파생한다 (DM-02). */
    fun isBiometricEnrolled(): Boolean = keyManager.biometricWrap() != null

    /** CRY-13 2 단계용 래핑 블롭. 암호문이다. */
    fun biometricBlob(): ByteArray? = keyManager.biometricWrap()

    /**
     * UX-03: 해제된 상태에서 마스터 비밀번호를 다시 확인한다. LOCK-05 백오프를 함께 적용한다 (CRY-12).
     * 세션 상태는 바꾸지 않는다.
     */
    suspend fun reauthenticate(password: CharArray): UnlockOutcome = withContext(kdfDispatcher) {
        if (_state.value != SessionState.Unlocked) return@withContext UnlockOutcome.CannotOpen
        when (val r = keyManager.unlock(password)) {
            is UnlockResult.Success -> {
                r.vaultKey.zeroize()
                UnlockOutcome.Success
            }
            UnlockResult.WrongPassword -> UnlockOutcome.WrongPassword
            is UnlockResult.LockedOut -> UnlockOutcome.LockedOut(r.remainingMs)
            UnlockResult.NoVault, UnlockResult.Corrupt -> UnlockOutcome.CannotOpen
        }
    }

    /**
     * CRY-12 4·5 단계. [wrap] 은 생체 인증을 마친 Cipher 로 VK 를 감싸 iv||ct||tag 를 돌려준다.
     * VK 는 [wrap] 안에서만 빌려준다 (LOCK-02). 잠겨 있으면 false.
     */
    fun enableBiometric(wrap: (vaultKey: ByteArray) -> ByteArray): Boolean {
        val blob = withVaultKey(wrap) ?: return false
        keyManager.setBiometricWrap(blob)
        return true
    }

    fun disableBiometric() {
        keyManager.setBiometricWrap(null)
    }

    /**
     * CRY-13. [unwrap] 은 생체 인증을 마친 Cipher 로 블롭을 풀어 VK 를 돌려준다. 풀지 못하면 null.
     * LOCK-05 대기 중에도 허용하고, 성공하면 실패 카운터를 초기화한다.
     */
    suspend fun unlockWithBiometric(unwrap: (blob: ByteArray) -> ByteArray?): UnlockOutcome = attempt.withLock {
        if (_state.value != SessionState.Locked) return UnlockOutcome.CannotOpen
        val blob = keyManager.biometricWrap() ?: return UnlockOutcome.BiometricInvalidated
        _state.value = SessionState.Unlocking
        try {
            withContext(kdfDispatcher) {
                val key = unwrap(blob)
                if (key == null) {
                    keyManager.setBiometricWrap(null)
                    return@withContext UnlockOutcome.BiometricInvalidated
                }
                try {
                    keyManager.recordSuccessfulUnlock()
                    openSession(key)
                    UnlockOutcome.Success
                } catch (e: Exception) {
                    key.zeroize()
                    UnlockOutcome.CannotOpen
                }
            }
        } finally {
            if (_state.value != SessionState.Unlocked) _state.value = stateFromDisk()
        }
    }

    /**
     * CRY-15 / UX-10. 현재 비밀번호를 확인하고 새 비밀번호로 VK 를 다시 감싼다. 생체 래핑은 폐기된다.
     * 세션은 그대로 해제 상태다 (VK 가 바뀌지 않는다). [current]·[new] 는 호출자가 지운다.
     */
    suspend fun changeMasterPassword(current: CharArray, new: CharArray): UnlockOutcome = withContext(kdfDispatcher) {
        if (_state.value != SessionState.Unlocked) return@withContext UnlockOutcome.CannotOpen
        when (val r = keyManager.changePassword(current, new)) {
            null -> UnlockOutcome.Success
            UnlockResult.WrongPassword -> UnlockOutcome.WrongPassword
            is UnlockResult.LockedOut -> UnlockOutcome.LockedOut(r.remainingMs)
            else -> UnlockOutcome.CannotOpen
        }
    }

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
