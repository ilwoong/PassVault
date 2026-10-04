package io.github.ilwoong.passvault.ui.onboarding

/** UX-01 강도 표시. 약해도 진행은 막지 않는다. */
enum class PasswordStrength { WEAK, FAIR, STRONG, VERY_STRONG }

/** UX-01: 마스터 비밀번호 최소 길이. 이보다 짧으면 진행할 수 없다. */
const val MIN_MASTER_PASSWORD_LENGTH = 12

/**
 * 길이 기반 + 흔한 비밀번호 대조. [String] 을 만들지 않고 입력 버퍼를 그대로 읽는다.
 */
fun passwordStrength(password: CharSequence): PasswordStrength = when {
    isCommon(password) || isSingleCharRepeat(password) -> PasswordStrength.WEAK
    password.length >= 20 -> PasswordStrength.VERY_STRONG
    password.length >= 16 -> PasswordStrength.STRONG
    else -> PasswordStrength.FAIR
}

private fun isSingleCharRepeat(s: CharSequence) = s.isNotEmpty() && s.all { it == s[0] }

private fun isCommon(s: CharSequence) = COMMON.any { c ->
    c.length == s.length && c.indices.all { i -> c[i] == s[i].lowercaseChar() }
}

/** 최소 길이(12) 이상인 흔한 비밀번호 소량. 더 짧은 것은 길이 규칙에서 이미 걸린다. */
private val COMMON = listOf(
    "123456789012",
    "1234567890123",
    "12345678901234",
    "password1234",
    "passwordpassword",
    "qwertyuiopas",
    "qwerty123456",
    "1q2w3e4r5t6y",
    "q1w2e3r4t5y6",
    "abcdefghijkl",
    "abc123456789",
    "iloveyou1234",
    "admin1234567",
    "letmein12345",
    "welcome12345",
    "asdfghjkl123",
    "zxcvbnm12345",
    "password1234!",
    "qwer1234qwer",
    "1111222233334444",
)
