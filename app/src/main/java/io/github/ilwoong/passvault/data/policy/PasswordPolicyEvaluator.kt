package io.github.ilwoong.passvault.data.policy

import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.PasswordPolicy

enum class Severity { ERROR, WARNING }

/** DM-10 위반 종류. ROTATION_DUE 만 경고 등급이다. */
enum class PolicyViolation(val severity: Severity = Severity.ERROR) {
    TOO_SHORT,
    TOO_LONG,
    MISSING_UPPER,
    MISSING_LOWER,
    MISSING_DIGIT,
    MISSING_SYMBOL,
    FORBIDDEN_UPPER,
    FORBIDDEN_LOWER,
    FORBIDDEN_DIGIT,
    FORBIDDEN_SYMBOL,
    DISALLOWED_SYMBOL,
    CONTAINS_SPACE,
    REPEAT_RUN,
    ROTATION_DUE(Severity.WARNING),
}

sealed interface PolicyReport {
    /** 정책을 입력하지 않았다. 아무 표시도 하지 않는다. */
    data object NotConfigured : PolicyReport

    /** 위반이 비어 있으면 정책 충족이다. */
    data class Evaluated(val violations: Set<PolicyViolation>) : PolicyReport {
        /** entry.hasPolicyViolation (DM-03) */
        val hasError: Boolean get() = violations.any { it.severity == Severity.ERROR }

        /** entry.hasRotationDue (DM-03) */
        val isRotationDue: Boolean get() = PolicyViolation.ROTATION_DUE in violations
    }
}

/** DM-10. Android 의존성 없는 순수 함수 (TST-02). */
object PasswordPolicyEvaluator {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun evaluate(
        password: CharArray,
        policy: PasswordPolicy?,
        passwordUpdatedAtEpochMs: Long?,
        nowEpochMs: Long,
    ): PolicyReport {
        policy ?: return PolicyReport.NotConfigured
        val v = mutableSetOf<PolicyViolation>()

        // 길이는 UTF-16 코드 유닛 수 (JavaScript length 와 같은 기준)
        policy.minLength?.let { if (password.size < it) v += PolicyViolation.TOO_SHORT }
        policy.maxLength?.let { if (password.size > it) v += PolicyViolation.TOO_LONG }

        v.checkClass(policy.upperRule, password.any(::isUpper), PolicyViolation.MISSING_UPPER, PolicyViolation.FORBIDDEN_UPPER)
        v.checkClass(policy.lowerRule, password.any(::isLower), PolicyViolation.MISSING_LOWER, PolicyViolation.FORBIDDEN_LOWER)
        v.checkClass(policy.digitRule, password.any(::isDigit), PolicyViolation.MISSING_DIGIT, PolicyViolation.FORBIDDEN_DIGIT)
        v.checkClass(policy.symbolRule, password.any(::isSymbol), PolicyViolation.MISSING_SYMBOL, PolicyViolation.FORBIDDEN_SYMBOL)

        // 빈 문자열은 null(모름)로 본다 — 비워 둔 입력란이 "전부 금지"가 되지 않게 (DM-08)
        val allowed = policy.allowedSymbols?.takeIf { it.isNotEmpty() }
        val forbidden = policy.forbiddenSymbols?.takeIf { it.isNotEmpty() }
        if (allowed != null && password.any { isSymbol(it) && it !in allowed }) v += PolicyViolation.DISALLOWED_SYMBOL
        if (forbidden != null && password.any { it in forbidden }) v += PolicyViolation.DISALLOWED_SYMBOL

        if (policy.disallowSpace && password.any { it.isWhitespace() }) v += PolicyViolation.CONTAINS_SPACE
        policy.maxRepeatRun?.let { if (longestRun(password) > it) v += PolicyViolation.REPEAT_RUN }

        val rotationDays = policy.rotationDays
        if (rotationDays != null && passwordUpdatedAtEpochMs != null &&
            nowEpochMs - passwordUpdatedAtEpochMs >= rotationDays * DAY_MS
        ) {
            v += PolicyViolation.ROTATION_DUE
        }
        return PolicyReport.Evaluated(v)
    }

    private fun MutableSet<PolicyViolation>.checkClass(
        rule: CharClassRule,
        present: Boolean,
        missing: PolicyViolation,
        forbidden: PolicyViolation,
    ) {
        when (rule) {
            CharClassRule.REQUIRED -> if (!present) this += missing
            CharClassRule.FORBIDDEN -> if (present) this += forbidden
            CharClassRule.ALLOWED, CharClassRule.UNKNOWN -> Unit
        }
    }

    private fun longestRun(s: CharArray): Int {
        var longest = 0
        var run = 0
        for (i in s.indices) {
            run = if (i > 0 && s[i] == s[i - 1]) run + 1 else 1
            if (run > longest) longest = run
        }
        return longest
    }

    // 판정은 ASCII 범위로 고정한다. 한글 등은 어느 종류에도 속하지 않는다 (DM-10).
    private fun isUpper(c: Char) = c in 'A'..'Z'
    private fun isLower(c: Char) = c in 'a'..'z'
    private fun isDigit(c: Char) = c in '0'..'9'

    /** "특수문자": ASCII 출력 가능 문자 중 영문·숫자·공백이 아닌 것. 0x21 ~ 0x7E. */
    private fun isSymbol(c: Char) = c in '!'..'~' && !isUpper(c) && !isLower(c) && !isDigit(c)
}
