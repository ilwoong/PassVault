package io.github.ilwoong.passvault.security

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CRY-03: AES-256-GCM 키 래핑. 출력 = nonce(12) || ciphertext || tag(16).
 *
 * `SecretKeySpec` 과 `Cipher` 가 키 사본을 내부에 들고 있고 지울 수단이 없다 (CRY-16 수용 한계).
 * 둘 다 함수 지역 객체로만 쓴다.
 */
class AesGcmKeyWrapper(private val random: SecureRandom = SecureRandom()) {

    fun wrap(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /**
     * 태그 검증에 실패하면 null. 키가 틀린 것과 데이터가 변조된 것을 구분하지 않는다 (CRY-11).
     * 반환값은 호출자가 지운다.
     */
    fun unwrap(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES),
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    companion object {
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
