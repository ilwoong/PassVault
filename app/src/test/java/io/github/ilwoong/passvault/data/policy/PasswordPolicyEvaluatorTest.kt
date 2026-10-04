package io.github.ilwoong.passvault.data.policy

import io.github.ilwoong.passvault.data.model.CharClassRule.ALLOWED
import io.github.ilwoong.passvault.data.model.CharClassRule.FORBIDDEN
import io.github.ilwoong.passvault.data.model.CharClassRule.REQUIRED
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.policy.PolicyViolation.CONTAINS_SPACE
import io.github.ilwoong.passvault.data.policy.PolicyViolation.DISALLOWED_SYMBOL
import io.github.ilwoong.passvault.data.policy.PolicyViolation.FORBIDDEN_DIGIT
import io.github.ilwoong.passvault.data.policy.PolicyViolation.FORBIDDEN_LOWER
import io.github.ilwoong.passvault.data.policy.PolicyViolation.FORBIDDEN_SYMBOL
import io.github.ilwoong.passvault.data.policy.PolicyViolation.FORBIDDEN_UPPER
import io.github.ilwoong.passvault.data.policy.PolicyViolation.MISSING_DIGIT
import io.github.ilwoong.passvault.data.policy.PolicyViolation.MISSING_LOWER
import io.github.ilwoong.passvault.data.policy.PolicyViolation.MISSING_SYMBOL
import io.github.ilwoong.passvault.data.policy.PolicyViolation.MISSING_UPPER
import io.github.ilwoong.passvault.data.policy.PolicyViolation.REPEAT_RUN
import io.github.ilwoong.passvault.data.policy.PolicyViolation.ROTATION_DUE
import io.github.ilwoong.passvault.data.policy.PolicyViolation.TOO_LONG
import io.github.ilwoong.passvault.data.policy.PolicyViolation.TOO_SHORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val DAY = 24L * 60 * 60 * 1000
private const val NOW = 1_000 * DAY

/** TST-02: DM-10 위반 종류별 표. 한 행 = (설명, 비밀번호, 정책, 비밀번호 변경 시각, 기대 위반). */
@RunWith(Parameterized::class)
class PasswordPolicyEvaluatorTest(
    private val case: String,
    private val password: String,
    private val policy: PasswordPolicy,
    private val updatedAt: Long?,
    private val expected: Set<PolicyViolation>,
) {

    @Test
    fun evaluate() {
        val report = PasswordPolicyEvaluator.evaluate(password.toCharArray(), policy, updatedAt, NOW)
        assertEquals(case, PolicyReport.Evaluated(expected), report)
    }

    companion object {
        private fun row(case: String, pw: String, policy: PasswordPolicy, vararg v: PolicyViolation, updatedAt: Long? = null) =
            arrayOf(case, pw, policy, updatedAt, v.toSet())

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            // --- 모름(UNKNOWN)·미입력은 검사하지 않는다 ---
            row("모든 필드 기본값이면 위반 없음", "a", PasswordPolicy()),
            row("rawNote 는 검사하지 않음", "a", PasswordPolicy(rawNote = "대문자 필수, 10자 이상")),

            // --- 길이 (UTF-16 코드 유닛) ---
            row("최소 길이 미달", "abc", PasswordPolicy(minLength = 4), TOO_SHORT),
            row("최소 길이 경계 충족", "abcd", PasswordPolicy(minLength = 4)),
            row("최대 길이 초과", "abcde", PasswordPolicy(maxLength = 4), TOO_LONG),
            row("최대 길이 경계 충족", "abcd", PasswordPolicy(maxLength = 4)),
            row("이모지는 2 코드 유닛", "a🔐", PasswordPolicy(minLength = 3)),
            row("빈 비밀번호도 평가", "", PasswordPolicy(minLength = 1), TOO_SHORT),

            // --- 문자 종류: REQUIRED ---
            row("대문자 필수 누락", "abc1!", PasswordPolicy(upperRule = REQUIRED), MISSING_UPPER),
            row("소문자 필수 누락", "ABC1!", PasswordPolicy(lowerRule = REQUIRED), MISSING_LOWER),
            row("숫자 필수 누락", "Abc!", PasswordPolicy(digitRule = REQUIRED), MISSING_DIGIT),
            row("특수문자 필수 누락", "Abc1", PasswordPolicy(symbolRule = REQUIRED), MISSING_SYMBOL),
            row(
                "네 종류 모두 필수 충족", "Abc1!",
                PasswordPolicy(upperRule = REQUIRED, lowerRule = REQUIRED, digitRule = REQUIRED, symbolRule = REQUIRED),
            ),

            // --- 문자 종류: FORBIDDEN ---
            row("대문자 금지 위반", "Abc", PasswordPolicy(upperRule = FORBIDDEN), FORBIDDEN_UPPER),
            row("소문자 금지 위반", "ABc", PasswordPolicy(lowerRule = FORBIDDEN), FORBIDDEN_LOWER),
            row("숫자 금지 위반", "abc1", PasswordPolicy(digitRule = FORBIDDEN), FORBIDDEN_DIGIT),
            row("특수문자 금지 위반", "abc!", PasswordPolicy(symbolRule = FORBIDDEN), FORBIDDEN_SYMBOL),

            // --- ALLOWED 는 있어도 없어도 위반이 아니다 ---
            row("허용: 있음", "A", PasswordPolicy(upperRule = ALLOWED)),
            row("허용: 없음", "a", PasswordPolicy(upperRule = ALLOWED)),

            // --- 판정은 ASCII 범위 ---
            row("한글은 소문자가 아님", "한글", PasswordPolicy(lowerRule = REQUIRED), MISSING_LOWER),
            row("한글은 특수문자가 아님", "한글", PasswordPolicy(symbolRule = FORBIDDEN)),
            row("전각 숫자는 숫자가 아님", "１２３", PasswordPolicy(digitRule = REQUIRED), MISSING_DIGIT),
            row("공백은 특수문자가 아님", "a b", PasswordPolicy(symbolRule = FORBIDDEN)),

            // --- 허용/금지 특수문자 집합 ---
            row("허용 집합 안의 특수문자", "a!@", PasswordPolicy(allowedSymbols = "!@#")),
            row("허용 집합 밖의 특수문자", "a!$", PasswordPolicy(allowedSymbols = "!@#"), DISALLOWED_SYMBOL),
            row("허용 집합은 영문·숫자에 적용 안 됨", "Ab1", PasswordPolicy(allowedSymbols = "!")),
            row("빈 허용 집합은 모름으로 취급", "a$", PasswordPolicy(allowedSymbols = "")),
            row("금지 집합 포함", "a<b", PasswordPolicy(forbiddenSymbols = "<>"), DISALLOWED_SYMBOL),
            row("금지 집합 미포함", "a!b", PasswordPolicy(forbiddenSymbols = "<>")),
            row("빈 금지 집합은 모름으로 취급", "a<", PasswordPolicy(forbiddenSymbols = "")),

            // --- 공백 ---
            row("공백 금지: 스페이스", "a b", PasswordPolicy(disallowSpace = true), CONTAINS_SPACE),
            row("공백 금지: 탭", "a\tb", PasswordPolicy(disallowSpace = true), CONTAINS_SPACE),
            row("공백 금지: 전각 공백", "a　b", PasswordPolicy(disallowSpace = true), CONTAINS_SPACE),
            row("공백 허용(기본)", "a b", PasswordPolicy()),

            // --- 연속 문자 ---
            row("연속 3자 > 허용 2", "xaaay", PasswordPolicy(maxRepeatRun = 2), REPEAT_RUN),
            row("연속 2자 = 허용 2", "xaay", PasswordPolicy(maxRepeatRun = 2)),
            row("대소문자는 다른 문자", "aAa", PasswordPolicy(maxRepeatRun = 1)),

            // --- 변경 주기 ---
            row("주기 경과(경계 포함)", "a", PasswordPolicy(rotationDays = 90), ROTATION_DUE, updatedAt = NOW - 90 * DAY),
            row("주기 미경과", "a", PasswordPolicy(rotationDays = 90), updatedAt = NOW - 90 * DAY + 1),
            row("변경 시각 모르면 판정 안 함", "a", PasswordPolicy(rotationDays = 1), updatedAt = null),

            // --- 여러 위반이 동시에 ---
            row(
                "복합 위반", "aaa b",
                PasswordPolicy(minLength = 10, upperRule = REQUIRED, disallowSpace = true, maxRepeatRun = 2),
                TOO_SHORT, MISSING_UPPER, CONTAINS_SPACE, REPEAT_RUN,
            ),
        )
    }
}

/** TST-02: 표에 담기 어려운 판정 규칙 */
class PasswordPolicyReportTest {

    @Test
    fun noPolicyMeansNotConfigured() {
        assertEquals(
            PolicyReport.NotConfigured,
            PasswordPolicyEvaluator.evaluate("x".toCharArray(), null, NOW, NOW),
        )
    }

    @Test
    fun rotationDueIsWarningNotError() {
        val r = PasswordPolicyEvaluator.evaluate("x".toCharArray(), PasswordPolicy(rotationDays = 1), 0, NOW)
            as PolicyReport.Evaluated
        assertTrue(r.isRotationDue)
        assertFalse("ROTATION_DUE 만으로는 hasPolicyViolation 이 아니다", r.hasError)
    }

    @Test
    fun anyOtherViolationIsError() {
        val r = PasswordPolicyEvaluator.evaluate("x".toCharArray(), PasswordPolicy(minLength = 5), null, NOW)
            as PolicyReport.Evaluated
        assertTrue(r.hasError)
        assertFalse(r.isRotationDue)
    }

    @Test
    fun everyViolationExceptRotationIsErrorSeverity() {
        for (v in PolicyViolation.entries) {
            val expected = if (v == ROTATION_DUE) Severity.WARNING else Severity.ERROR
            assertEquals(v.name, expected, v.severity)
        }
    }
}
