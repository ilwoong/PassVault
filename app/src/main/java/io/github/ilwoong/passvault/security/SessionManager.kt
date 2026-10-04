package io.github.ilwoong.passvault.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LOCK-01. Unlocked 에서만 세션이 VK 를 내주고 금고 화면이 있다.
 * Unlocking·Creating 은 전환 중이다 — 마지막 단계에서 DB 가 열리고, BK-05 복구는 항목 기록까지 Unlocking 에서 한다.
 */
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

    /** BK-05: 금고를 새로 만들기 직전에 기존 자원(DB 파일)을 지운다. 닫힌 상태에서만 불린다. */
    fun discard() = Unit
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

    /** LOCK-03: 전환 중에 사용자가 떠났다. 전환이 끝나는 즉시 잠근다. [keyLock] 으로 보호한다. */
    private var lockPending = false

    /** UX-01 4 단계: 금고를 방금 만들었는지. 한 번 읽으면 사라진다. */
    @Volatile
    private var justCreated = false

    /** CRY-10. 성공하면 Unlocked — 그 사이 사용자가 떠났으면 Locked (LOCK-03). [password] 는 호출자가 지운다. */
    suspend fun createVault(password: CharArray): Unit = attempt.withLock {
        check(_state.value == SessionState.NoVault) { "금고가 이미 있다" }
        beginTransition(SessionState.Creating)
        try {
            withContext(kdfDispatcher) {
                val key = keyManager.createVault(password)
                // 상태가 Unlocked 로 바뀌는 순간 금고 화면이 읽는다 — 그 전에 세워 둔다 (UX-01 4 단계)
                justCreated = true
                openSession(key)
            }
        } finally {
            if (_state.value != SessionState.Unlocked) {
                justCreated = false
                _state.value = stateFromDisk()
            }
        }
    }

    /**
     * CRY-11, LOCK-05. [password] 는 호출자가 지운다.
     * 해제하는 사이 사용자가 떠났으면 Success 를 돌려주되 세션은 Locked 로 끝난다 (LOCK-03).
     */
    suspend fun unlock(password: CharArray): UnlockOutcome = attempt.withLock {
        when (_state.value) {
            SessionState.Unlocked -> return UnlockOutcome.Success
            SessionState.Locked -> Unit
            else -> return UnlockOutcome.CannotOpen
        }
        beginTransition(SessionState.Unlocking)
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

    /** BK-03: 백업 비밀번호가 마스터와 같은지. 실패 횟수에 넣지 않는다. */
    suspend fun matchesMasterPassword(password: CharArray): Boolean =
        withContext(kdfDispatcher) { keyManager.matchesMasterPassword(password) }

    /**
     * BK-05: 백업 복호화가 성공한 뒤에만 부른다. 새 비밀번호로 금고를 다시 만들고, 기존 DB 를 지우고,
     * 새 DB 를 연 상태에서 [populate] 로 항목을 기록한 **뒤에** Unlocked 로 바꾼다.
     * 상태가 먼저 바뀌면 화면 분기가 바뀌어 기록 작업이 취소될 수 있다 (UX-00).
     * 그 사이 사용자가 떠났으면 기록을 마친 뒤 Locked 로 끝나고 false 를 돌려준다 (LOCK-03).
     */
    suspend fun recreateVault(newPassword: CharArray, populate: suspend () -> Unit): Boolean = attempt.withLock {
        check(_state.value == SessionState.Locked || _state.value == SessionState.Corrupt) { "열려 있는 금고는 다시 만들지 않는다" }
        beginTransition(SessionState.Unlocking)
        try {
            withContext(kdfDispatcher) {
                val key = keyManager.recreateVault(newPassword)
                try {
                    resource.discard()
                    resource.onUnlocked(key)
                } catch (e: Throwable) {
                    key.zeroize()
                    throw e
                }
                try {
                    populate()
                } catch (e: Throwable) {
                    resource.onLocked()
                    key.zeroize()
                    throw e
                }
                commit(key)
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
        beginTransition(SessionState.Unlocking)
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
     * LOCK-04. 순서: 상태 전환 → (UI 가 상태 변화를 보고 금고 화면의 ViewModel 을 즉시 비움, 4 단계) → DB close → VK 제로화.
     * 클립보드 삭제(6 단계)는 잠금을 부른 쪽이 한다 — 수동 잠금과 화면 꺼짐일 때만 지우기 때문이다.
     *
     * [deferIfBusy]: 사용자가 떠났다는 신호(백그라운드 전환·화면 꺼짐)다. 해제·생성·복구가 진행 중이면
     * 끊지 않고, 끝나는 즉시 잠근다 (LOCK-03). 유휴 판정은 false 로 부른다 — 해제 전의 유휴는 의미가 없다.
     */
    fun lock(deferIfBusy: Boolean = false) {
        synchronized(keyLock) {
            when (_state.value) {
                SessionState.Unlocked -> {
                    _state.value = SessionState.Locked
                    try {
                        resource.onLocked()
                    } finally {
                        vaultKey?.zeroize()
                        vaultKey = null
                    }
                }
                SessionState.Unlocking, SessionState.Creating -> if (deferIfBusy) lockPending = true
                else -> Unit
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
        commit(key)
    }

    /** 이전 전환에서 남은 잠금 요청은 버린다. */
    private fun beginTransition(state: SessionState) = synchronized(keyLock) {
        lockPending = false
        _state.value = state
    }

    /**
     * 전환의 마지막 단계. DB 는 이미 열려 있다. 그 사이 사용자가 떠났으면 Unlocked 로 가지 않고 닫는다 (LOCK-03).
     * 그때 상태는 호출한 쪽의 finally 가 Locked 로 되돌린다. Unlocked 가 됐으면 true.
     */
    private fun commit(key: ByteArray): Boolean = synchronized(keyLock) {
        if (lockPending) {
            lockPending = false
            try {
                resource.onLocked()
            } finally {
                key.zeroize()
            }
            false
        } else {
            vaultKey = key
            _state.value = SessionState.Unlocked
            true
        }
    }

    private fun stateFromDisk(): SessionState = when (keyManager.vaultStatus()) {
        MetaReadResult.Absent -> SessionState.NoVault
        MetaReadResult.Corrupt -> SessionState.Corrupt
        is MetaReadResult.Present -> SessionState.Locked
    }
}
