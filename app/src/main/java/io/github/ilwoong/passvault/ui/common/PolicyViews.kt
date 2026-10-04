package io.github.ilwoong.passvault.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.policy.PolicyReport
import io.github.ilwoong.passvault.data.policy.PolicyViolation
import io.github.ilwoong.passvault.data.policy.Severity
import io.github.ilwoong.passvault.data.policy.hasCheckableRule

/** DM-10 위반 종류별 문구. */
@Composable
fun violationText(v: PolicyViolation): String = stringResource(
    when (v) {
        PolicyViolation.TOO_SHORT -> R.string.v_too_short
        PolicyViolation.TOO_LONG -> R.string.v_too_long
        PolicyViolation.MISSING_UPPER -> R.string.v_missing_upper
        PolicyViolation.MISSING_LOWER -> R.string.v_missing_lower
        PolicyViolation.MISSING_DIGIT -> R.string.v_missing_digit
        PolicyViolation.MISSING_SYMBOL -> R.string.v_missing_symbol
        PolicyViolation.FORBIDDEN_UPPER -> R.string.v_forbidden_upper
        PolicyViolation.FORBIDDEN_LOWER -> R.string.v_forbidden_lower
        PolicyViolation.FORBIDDEN_DIGIT -> R.string.v_forbidden_digit
        PolicyViolation.FORBIDDEN_SYMBOL -> R.string.v_forbidden_symbol
        PolicyViolation.DISALLOWED_SYMBOL -> R.string.v_disallowed_symbol
        PolicyViolation.CONTAINS_SPACE -> R.string.v_contains_space
        PolicyViolation.REPEAT_RUN -> R.string.v_repeat_run
        PolicyViolation.ROTATION_DUE -> R.string.v_rotation_due
    },
)

@Composable
fun ruleText(rule: CharClassRule): String = stringResource(
    when (rule) {
        CharClassRule.UNKNOWN -> R.string.rule_unknown
        CharClassRule.REQUIRED -> R.string.rule_required
        CharClassRule.ALLOWED -> R.string.rule_allowed
        CharClassRule.FORBIDDEN -> R.string.rule_forbidden
    },
)

/** UX-05: 기록된 규칙 요약. 모름(UNKNOWN)은 적지 않는다. */
@Composable
fun policySummary(p: PasswordPolicy): List<String> = buildList {
    val min = p.minLength
    val max = p.maxLength
    when {
        min != null && max != null -> add(stringResource(R.string.summary_length_range, min, max))
        min != null -> add(stringResource(R.string.summary_length_min, min))
        max != null -> add(stringResource(R.string.summary_length_max, max))
    }
    for ((label, rule) in listOf(
        R.string.policy_upper to p.upperRule,
        R.string.policy_lower to p.lowerRule,
        R.string.policy_digit to p.digitRule,
        R.string.policy_symbol to p.symbolRule,
    )) {
        if (rule != CharClassRule.UNKNOWN) add(stringResource(R.string.summary_rule, stringResource(label), ruleText(rule)))
    }
    p.allowedSymbols?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.summary_allowed_symbols, it)) }
    p.forbiddenSymbols?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.summary_forbidden_symbols, it)) }
    p.maxRepeatRun?.let { add(stringResource(R.string.summary_max_repeat, it)) }
    if (p.disallowSpace) add(stringResource(R.string.summary_no_space))
    p.rotationDays?.let { add(stringResource(R.string.summary_rotation, it)) }
}

/**
 * 충족·위반 판정. 검사할 수 있는 규칙이 없으면 아무것도 그리지 않는다 —
 * 검사하지 않은 것을 충족했다고 말하지 않는다 (UX-05).
 */
@Composable
fun PolicyVerdict(policy: PasswordPolicy?, report: PolicyReport) {
    if (policy == null || !policy.hasCheckableRule || report !is PolicyReport.Evaluated) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (report.violations.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.policy_satisfied), color = MaterialTheme.colorScheme.primary)
            }
        }
        for (v in report.violations.sortedBy { it.ordinal }) {
            Text(
                "• " + violationText(v),
                color = if (v.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
