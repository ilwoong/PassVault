package io.github.ilwoong.passvault.ui.common

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.ilwoong.passvault.security.BiometricKeyStore
import io.github.ilwoong.passvault.security.SessionManager
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * 생체 인증을 요청하고, 성공하면 인증된 Cipher 를 돌려준다. 취소·오류·잠김이면 null.
 * 개별 인식 실패(onAuthenticationFailed)는 프롬프트가 계속 떠 있으므로 기다린다.
 */
suspend fun FragmentActivity.authenticateCipher(title: String, negative: String, cipher: Cipher): Cipher? =
    suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        cont.invokeOnCancellation { executor.execute { prompt.cancelAuthentication() } }
    }

/** CRY-12 2·4·5 단계. 설정(재인증 후)과 생성 직후 제안(UX-01 4 단계)이 함께 쓴다. */
class BiometricEnroller @Inject constructor(
    private val session: SessionManager,
    private val keyStore: BiometricKeyStore,
) {
    val isAvailable: Boolean get() = keyStore.isAvailable()

    /** 새 키를 만들고 등록용 Cipher 를 돌려준다. 키를 만들 수 없으면 null (생체 등록이 방금 사라진 경우 등). */
    fun newCipher(): Cipher? = try {
        keyStore.newEncryptCipher()
    } catch (e: Exception) {
        null
    }

    /** 인증된 Cipher 로 VK 를 감싸 저장한다. */
    fun complete(authenticated: Cipher): Boolean = try {
        session.enableBiometric { vk -> keyStore.wrap(authenticated, vk) }
    } catch (e: Exception) {
        false
    }

    fun disable() {
        session.disableBiometric()
        keyStore.deleteKey()
    }
}
