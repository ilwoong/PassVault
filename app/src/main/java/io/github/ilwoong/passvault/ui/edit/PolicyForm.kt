package io.github.ilwoong.passvault.ui.edit

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.PasswordPolicy
import io.github.ilwoong.passvault.data.policy.isEmpty

/** UX-07 정책 편집 상태. 비밀이 아니지만 다른 입력과 같이 저장 상태에 넣지 않는다. */
class PolicyForm(initial: PasswordPolicy?) {
    val minLength = TextFieldState(initial?.minLength?.toString().orEmpty())
    val maxLength = TextFieldState(initial?.maxLength?.toString().orEmpty())
    val maxRepeatRun = TextFieldState(initial?.maxRepeatRun?.toString().orEmpty())
    val rotationDays = TextFieldState(initial?.rotationDays?.toString().orEmpty())
    val allowedSymbols = TextFieldState(initial?.allowedSymbols.orEmpty())
    val forbiddenSymbols = TextFieldState(initial?.forbiddenSymbols.orEmpty())
    val rawNote = TextFieldState(initial?.rawNote.orEmpty())

    var upper by mutableStateOf(initial?.upperRule ?: CharClassRule.UNKNOWN)
    var lower by mutableStateOf(initial?.lowerRule ?: CharClassRule.UNKNOWN)
    var digit by mutableStateOf(initial?.digitRule ?: CharClassRule.UNKNOWN)
    var symbol by mutableStateOf(initial?.symbolRule ?: CharClassRule.UNKNOWN)
    var disallowSpace by mutableStateOf(initial?.disallowSpace ?: false)

    private val texts = listOf(minLength, maxLength, maxRepeatRun, rotationDays, allowedSymbols, forbiddenSymbols, rawNote)
    private val initialTexts = texts.map { it.text.toString() }
    private val initialFlags = listOf(upper, lower, digit, symbol, disallowSpace)

    val numberFields: List<TextFieldState> get() = listOf(minLength, maxLength, maxRepeatRun, rotationDays)

    val minMaxOk: Boolean
        get() {
            val min = minLength.positiveIntOrNull()
            val max = maxLength.positiveIntOrNull()
            return min == null || max == null || min <= max
        }

    val isValid: Boolean get() = numberFields.all { isPositiveOrEmpty(it) } && minMaxOk

    val isDirty: Boolean
        get() = texts.zip(initialTexts).any { (s, init) -> !s.text.contentEquals(init) } ||
            listOf(upper, lower, digit, symbol, disallowSpace) != initialFlags

    /** 아무것도 기록하지 않았으면 null — 정책 행을 만들지 않는다 (UX-07). [isValid] 일 때만 부른다. */
    fun toPolicy(): PasswordPolicy? = PasswordPolicy(
        minLength = minLength.positiveIntOrNull(),
        maxLength = maxLength.positiveIntOrNull(),
        upperRule = upper,
        lowerRule = lower,
        digitRule = digit,
        symbolRule = symbol,
        allowedSymbols = allowedSymbols.text.toString().ifEmpty { null },
        forbiddenSymbols = forbiddenSymbols.text.toString().ifEmpty { null },
        maxRepeatRun = maxRepeatRun.positiveIntOrNull(),
        disallowSpace = disallowSpace,
        rotationDays = rotationDays.positiveIntOrNull(),
        rawNote = rawNote.text.toString().ifBlank { null },
    ).takeUnless { it.isEmpty }
}

fun isPositiveOrEmpty(s: TextFieldState): Boolean = s.text.isEmpty() || s.positiveIntOrNull() != null

private fun TextFieldState.positiveIntOrNull(): Int? = text.toString().toIntOrNull()?.takeIf { it >= 1 }
