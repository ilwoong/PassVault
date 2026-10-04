package io.github.ilwoong.passvault.ui

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.github.ilwoong.passvault.data.db.RotationRow
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.EntrySummary
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.policy.PasswordPolicyEvaluator
import io.github.ilwoong.passvault.data.policy.hasCheckableRule
import io.github.ilwoong.passvault.data.policy.isEmpty
import io.github.ilwoong.passvault.ui.edit.PolicyForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 24L * 60 * 60 * 1000

class PolicyUiLogicTest {

    // --- UX-07: 비어 있으면 정책 행을 만들지 않는다 ---

    @Test
    fun emptyPolicyDefinition() {
        assertTrue(PasswordPolicy().isEmpty)
        assertTrue("빈 특수문자 집합은 null 과 같다", PasswordPolicy(allowedSymbols = "", forbiddenSymbols = "").isEmpty)
        assertTrue("공백만 있는 원문은 비어 있다", PasswordPolicy(rawNote = "  ").isEmpty)
        assertFalse(PasswordPolicy(rawNote = "규칙").isEmpty)
        assertFalse(PasswordPolicy(upperRule = CharClassRule.ALLOWED).isEmpty)
        assertFalse(PasswordPolicy(disallowSpace = true).isEmpty)
    }

    @Test
    fun checkableRuleDefinition() {
        assertFalse(PasswordPolicy(rawNote = "원문만").hasCheckableRule)
        assertFalse("허용은 위반을 만들 수 없다", PasswordPolicy(upperRule = CharClassRule.ALLOWED).hasCheckableRule)
        assertTrue(PasswordPolicy(upperRule = CharClassRule.REQUIRED).hasCheckableRule)
        assertTrue(PasswordPolicy(symbolRule = CharClassRule.FORBIDDEN).hasCheckableRule)
        assertTrue(PasswordPolicy(minLength = 8).hasCheckableRule)
        assertTrue(PasswordPolicy(rotationDays = 90).hasCheckableRule)
        assertTrue(PasswordPolicy(allowedSymbols = "!").hasCheckableRule)
        assertFalse(PasswordPolicy(allowedSymbols = "").hasCheckableRule)
    }

    @Test
    fun untouchedFormProducesNoPolicy() {
        assertNull(PolicyForm(null).toPolicy())
        assertFalse(PolicyForm(null).isDirty)
    }

    @Test
    fun allUnknownRulesProduceNoPolicyAndNoViolations() {
        val form = PolicyForm(null).apply {
            upper = CharClassRule.UNKNOWN
            lower = CharClassRule.UNKNOWN
        }
        assertNull(form.toPolicy())
        // 평가기 수준에서도 위반이 없다 (DM-09)
        val r = PasswordPolicyEvaluator.evaluate("x".toCharArray(), PasswordPolicy(), null, 0)
        assertEquals(io.github.ilwoong.passvault.data.policy.PolicyReport.Evaluated(emptySet()), r)
    }

    @Test
    fun formRoundTripsAnExistingPolicy() {
        val p = PasswordPolicy(
            minLength = 10, maxLength = 20, upperRule = CharClassRule.REQUIRED, symbolRule = CharClassRule.FORBIDDEN,
            allowedSymbols = "!@", maxRepeatRun = 2, disallowSpace = true, rotationDays = 90, rawNote = "원문",
        )
        val form = PolicyForm(p)
        assertEquals(p, form.toPolicy())
        assertFalse(form.isDirty)
    }

    @Test
    fun numberValidation() {
        val form = PolicyForm(null)
        form.minLength.setTextAndPlaceCursorAtEnd("0")
        assertFalse("1 이상이어야 한다", form.isValid)
        form.minLength.setTextAndPlaceCursorAtEnd("12")
        form.maxLength.setTextAndPlaceCursorAtEnd("8")
        assertFalse("최소 > 최대", form.isValid)
        assertFalse(form.minMaxOk)
        form.maxLength.setTextAndPlaceCursorAtEnd("20")
        assertTrue(form.isValid)
        assertEquals(PasswordPolicy(minLength = 12, maxLength = 20), form.toPolicy())
        assertTrue(form.isDirty)
    }

    @Test
    fun ruleChangeMakesFormDirty() {
        val form = PolicyForm(null)
        form.digit = CharClassRule.REQUIRED
        assertTrue(form.isDirty)
        assertEquals(PasswordPolicy(digitRule = CharClassRule.REQUIRED), form.toPolicy())
    }

    // --- DM-03: 목록 배지 경로에 비밀번호가 없다 ---

    @Test
    fun listAndRotationRowsCarryNoSecretColumns() {
        val secretish = listOf("password", "memo", "body", "number", "cvc", "pin", "docnumber")
        for (cls in listOf(EntrySummary::class.java, RotationRow::class.java)) {
            val names = cls.declaredFields.map { it.name.lowercase() }
            assertTrue("${cls.simpleName}: $names", names.none { n -> secretish.any { n == it } })
        }
    }

    @Test
    fun rotationRuleIsSharedWithEvaluator() {
        assertTrue(PasswordPolicyEvaluator.isRotationDue(0, 30, 30 * DAY))
        assertFalse(PasswordPolicyEvaluator.isRotationDue(0, 30, 30 * DAY - 1))
        assertFalse(PasswordPolicyEvaluator.isRotationDue(null, 30, 100 * DAY))
        assertFalse(PasswordPolicyEvaluator.isRotationDue(0, null, 100 * DAY))
    }
}
