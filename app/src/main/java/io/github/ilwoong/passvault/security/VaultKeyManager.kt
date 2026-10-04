package io.github.ilwoong.passvault.security

import java.nio.ByteBuffer
import java.security.SecureRandom

sealed interface UnlockResult {
    /** [vaultKey] 는 호출자 소유다. M3 에서 SessionManager 가 넘겨받는다 (LOCK-02). */
    class Success(val vaultKey: ByteArray) : UnlockResult

    /** GCM 태그 실패. 비밀번호 오류와 메타 변조를 구분하지 않는다 (CRY-11). */
    data object WrongPassword : UnlockResult

    /** LOCK-05 대기 중. KDF 를 실행하지 않았고 실패 횟수도 늘리지 않았다. */
    data class LockedOut(val remainingMs: Long) : UnlockResult
    data object NoVault : UnlockResult
    data object Corrupt : UnlockResult
}

/**
 * CRY-10 금고 생성, CRY-11 잠금 해제, LOCK-05 실패 백오프.
 *
 * [CharArray] 비밀번호는 호출자 소유이며 호출자가 지운다. 메서드는 수 초 걸릴 수 있다 (ARC-05).
 */
class VaultKeyManager(
    private val metaStore: VaultMetaStore,
    private val deriver: Argon2KeyDeriver,
    private val wrapper: AesGcmKeyWrapper,
    private val clocks: Clocks,
    private val random: SecureRandom = SecureRandom(),
) {

    fun vaultStatus(): MetaReadResult = metaStore.read()

    /** LOCK-05. 금고가 없거나 손상이면 0. */
    fun lockoutRemainingMs(): Long =
        (metaStore.read() as? MetaReadResult.Present)?.let { remainingLockoutMs(it.meta, clocks) } ?: 0

    /**
     * CRY-10. 반환된 VK 는 호출자 소유다.
     *
     * 금고가 [MetaReadResult.Absent] 일 때만 만든다. Corrupt 위에 덮어쓰면 복구 가능성을
     * 영구히 없애므로 거부한다 (DM-01, ARC-06).
     */
    fun createVault(password: CharArray): ByteArray {
        check(metaStore.read() == MetaReadResult.Absent) { "vault_meta 가 이미 있다. 덮어쓰지 않는다" }

        val params = deriver.calibrate()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val vaultKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val wrapped = password.toUtf8Bytes().useThenZeroize { pw ->
                deriver.derive(pw, salt, params).useThenZeroize { mk ->
                    wrapper.wrap(mk, vaultKey, aadFor(params))
                }
            }
            metaStore.write(
                VaultMeta(
                    kdfSalt = salt,
                    kdfParams = params,
                    wrappedVkByMk = wrapped,
                    wrappedVkByBio = null,
                    failedAttempts = 0,
                    lockoutUntilEpochMs = 0,
                    vaultCreatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return vaultKey
        } catch (e: Throwable) {
            vaultKey.zeroize()
            throw e
        }
    }

    /**
     * CRY-11. 검증용 해시를 따로 두지 않는다 — GCM 태그가 유일한 검증 수단이다.
     *
     * LOCK-05: 대기 중이면 KDF 를 실행하지 않는다. 실패하면 실패 상태를 원자적으로 기록하고,
     * 성공하면 초기화한다.
     */
    fun unlock(password: CharArray): UnlockResult {
        val meta = when (val read = metaStore.read()) {
            MetaReadResult.Absent -> return UnlockResult.NoVault
            MetaReadResult.Corrupt -> return UnlockResult.Corrupt
            is MetaReadResult.Present -> read.meta
        }
        remainingLockoutMs(meta, clocks).let { if (it > 0) return UnlockResult.LockedOut(it) }

        val vaultKey = password.toUtf8Bytes().useThenZeroize { pw ->
            deriver.derive(pw, meta.kdfSalt, meta.kdfParams).useThenZeroize { mk ->
                wrapper.unwrap(mk, meta.wrappedVkByMk, aadFor(meta.kdfParams))
            }
        }
        if (vaultKey == null) {
            metaStore.write(meta.afterFailedUnlock(clocks))
            return UnlockResult.WrongPassword
        }
        if (meta.hasFailureState) {
            try {
                metaStore.write(meta.afterSuccessfulUnlock())
            } catch (e: Throwable) {
                vaultKey.zeroize()
                throw e
            }
        }
        return UnlockResult.Success(vaultKey)
    }
}

/** CRY-03: ASCII "pv:vk:v1" || m || t || p (u32 BE). */
internal fun aadFor(params: KdfParams): ByteArray =
    ByteBuffer.allocate(AAD_PREFIX.size + 12)
        .put(AAD_PREFIX)
        .putInt(params.memoryKiB)
        .putInt(params.iterations)
        .putInt(params.parallelism)
        .array()

private val AAD_PREFIX = "pv:vk:v1".toByteArray(Charsets.US_ASCII)
