package io.github.ilwoong.passvault.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CRY-07: 생체 해제용 Keystore 키. 키는 Keystore 밖으로 나오지 않는다 — 생체 인증을 마친 Cipher 만 쓸 수 있다.
 * security 패키지에서 Android 프레임워크를 쓰는 유일한 예외다 (ARC-03).
 */
class BiometricKeyStore(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** CRY-14: Class 3 생체가 등록돼 있어야 제공한다. */
    fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** CRY-12 2 단계. 기존 키는 지우고 새로 만든다. 등록용 Cipher 를 돌려준다 (생체 인증 전). */
    fun newEncryptCipher(): Cipher {
        deleteKey()
        val key = try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** 인증된 Cipher 로 VK 를 감싼다. iv(12) || ct || tag(16) — DM-01 의 60 바이트 필드. */
    fun wrap(authenticated: Cipher, vaultKey: ByteArray): ByteArray = authenticated.iv + authenticated.doFinal(vaultKey)

    /**
     * CRY-13 2 단계. 해제용 Cipher (생체 인증 전). 키가 무효화됐으면 null —
     * 호출자는 래핑을 지우고 비밀번호로 해제하도록 안내한다 (SEC-11).
     */
    fun decryptCipher(blob: ByteArray): Cipher? {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, AesGcmKeyWrapper.NONCE_BYTES))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            null
        }
    }

    /** 인증된 Cipher 로 VK 를 푼다. 블롭이 손상됐으면 null (CRY-13 5 단계). 반환값은 호출자가 지운다. */
    fun unwrap(authenticated: Cipher, blob: ByteArray): ByteArray? = try {
        authenticated.doFinal(blob, AesGcmKeyWrapper.NONCE_BYTES, blob.size - AesGcmKeyWrapper.NONCE_BYTES)
    } catch (e: AEADBadTagException) {
        null
    }

    fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true) // SEC-11
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    // API 28–29: 매 사용 인증, 생체 전용
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "passvault_biometric_vk"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
