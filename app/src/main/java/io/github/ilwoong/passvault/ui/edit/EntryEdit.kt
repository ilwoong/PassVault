package io.github.ilwoong.passvault.ui.edit

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import io.github.ilwoong.passvault.data.model.CharClassRule
import io.github.ilwoong.passvault.data.model.MAX_TEXT_LENGTH
import io.github.ilwoong.passvault.data.policy.PasswordPolicyEvaluator
import io.github.ilwoong.passvault.data.policy.PolicyReport
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.common.PolicyVerdict
import io.github.ilwoong.passvault.ui.common.ruleText
import io.github.ilwoong.passvault.ui.copyToCharArray
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.model.EntryType
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.ui.common.typeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 편집 대상. [entry] 가 null 이면 새 항목이다. */
class EditSource(val type: EntryType, val entry: Entry?)

@HiltViewModel
class EntryEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: EntryRepository,
) : ViewModel() {
    private val id: String? = savedState["id"]
    private val newType: EntryType? = savedState.get<String>("type")?.let(EntryType::valueOf)

    var source by mutableStateOf<EditSource?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            source = if (id != null) {
                repo.get(id)?.let { EditSource(it.content.type, it) }
            } else {
                EditSource(checkNotNull(newType), null)
            }
        }
    }

    fun save(draft: EntryDraft, onSaved: (String) -> Unit) {
        saving = true
        failed = false
        viewModelScope.launch {
            try {
                onSaved(repo.save(draft))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            } finally {
                saving = false
            }
        }
    }
}

@Composable
fun EntryEditRoute(
    onSaved: (id: String, wasNew: Boolean) -> Unit,
    onExit: () -> Unit,
    vm: EntryEditViewModel = hiltViewModel(),
) {
    val source = vm.source ?: return
    val form = remember(source) { EntryForm(source.type, source.entry) }
    EntryEditScreen(
        form = form,
        saving = vm.saving,
        failed = vm.failed,
        onSave = { vm.save(form.toDraft()) { id -> onSaved(id, form.isNew) } },
        onExit = onExit,
    )
}

/** UX-06 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    form: EntryForm,
    saving: Boolean,
    failed: Boolean,
    onSave: () -> Unit,
    onExit: () -> Unit,
    nowEpochMs: Long = System.currentTimeMillis(),
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    val leave = { if (form.isDirty) confirmingDiscard = true else onExit() }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (form.isNew) stringResource(R.string.new_entry_title, typeLabel(form.type))
                        else stringResource(R.string.edit_entry_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = form.isValid && !saving) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (field in form.fields) FieldEditor(field, isTitle = field.key == FieldKey.TITLE)
            form.policy?.let { PolicySection(form, it, nowEpochMs) }
            if (failed) Text(stringResource(R.string.error_save_failed), color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDiscard = false; onExit() }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) }
            },
        )
    }
}

@Composable
private fun FieldEditor(field: FormField, isTitle: Boolean) {
    val modifier = Modifier.fillMaxWidth()
    when (field.kind) {
        FieldKind.SECRET, FieldKind.SECRET_NUMBER -> {
            // 편집 중 확인할 수 있게 표시 토글을 둔다. 토글 상태도 저장하지 않는다.
            var visible by remember { mutableStateOf(false) }
            OutlinedSecureTextField(
                state = field.state,
                modifier = modifier,
                label = { Text(stringResource(field.label)) },
                inputTransformation = InputTransformation.maxLength(field.maxLength),
                textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.kind == FieldKind.SECRET_NUMBER) KeyboardType.NumberPassword else KeyboardType.Password,
                ),
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) {
                        Text(stringResource(if (visible) R.string.action_hide else R.string.action_show))
                    }
                },
            )
        }
        FieldKind.MULTILINE -> OutlinedTextField(
            state = field.state,
            modifier = modifier,
            label = { Text(stringResource(field.label)) },
            inputTransformation = InputTransformation.maxLength(field.maxLength),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        else -> {
            val invalid = !field.isValid
            val titleMissing = isTitle && field.state.text.isBlank()
            OutlinedTextField(
                state = field.state,
                modifier = modifier,
                label = { Text(stringResource(field.label)) },
                inputTransformation = InputTransformation.maxLength(field.maxLength),
                lineLimits = TextFieldLineLimits.SingleLine,
                isError = invalid,
                supportingText = when {
                    titleMissing -> { { Text(stringResource(R.string.title_required)) } }
                    invalid -> { { Text(stringResource(errorFor(field.kind))) } }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = when (field.kind) {
                        FieldKind.MONTH, FieldKind.YEAR -> KeyboardType.Number
                        FieldKind.PLAIN -> if (field.key == FieldKey.URL) KeyboardType.Uri else KeyboardType.Text
                        else -> KeyboardType.Text
                    },
                ),
            )
        }
    }
}

private fun errorFor(kind: FieldKind) = when (kind) {
    FieldKind.MONTH -> R.string.invalid_month
    FieldKind.YEAR -> R.string.invalid_year
    else -> R.string.invalid_date
}

/** UX-07: 접을 수 있는 정책 섹션. 기본 접힘 — 기본 입력 흐름을 방해하지 않는다. */
@Composable
private fun PolicySection(form: EntryForm, policy: PolicyForm, now: Long) {
    var expanded by remember { mutableStateOf(false) }
    val recorded = policy.isValid && policy.toPolicy() != null
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable { expanded = !expanded },
            headlineContent = { Text(stringResource(R.string.policy_section_title)) },
            supportingContent = {
                Text(stringResource(if (recorded) R.string.policy_recorded else R.string.policy_not_recorded))
            },
            trailingContent = {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
                )
            },
        )
        if (!expanded) return@OutlinedCard
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(policy.minLength, R.string.policy_min_length, Modifier.weight(1f), extraError = !policy.minMaxOk)
                NumberField(policy.maxLength, R.string.policy_max_length, Modifier.weight(1f), extraError = !policy.minMaxOk)
            }
            if (!policy.minMaxOk) Text(stringResource(R.string.invalid_min_max), color = MaterialTheme.colorScheme.error)
            RuleSelector(R.string.policy_upper, policy.upper) { policy.upper = it }
            RuleSelector(R.string.policy_lower, policy.lower) { policy.lower = it }
            RuleSelector(R.string.policy_digit, policy.digit) { policy.digit = it }
            RuleSelector(R.string.policy_symbol, policy.symbol) { policy.symbol = it }
            OutlinedTextField(
                state = policy.allowedSymbols,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.policy_allowed_symbols)) },
                inputTransformation = InputTransformation.maxLength(MAX_TEXT_LENGTH),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            )
            OutlinedTextField(
                state = policy.forbiddenSymbols,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.policy_forbidden_symbols)) },
                inputTransformation = InputTransformation.maxLength(MAX_TEXT_LENGTH),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            )
            NumberField(policy.maxRepeatRun, R.string.policy_max_repeat, Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = policy.disallowSpace, role = Role.Switch, onValueChange = { policy.disallowSpace = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.policy_disallow_space), Modifier.weight(1f))
                Switch(checked = policy.disallowSpace, onCheckedChange = null)
            }
            NumberField(policy.rotationDays, R.string.policy_rotation_days, Modifier.fillMaxWidth())
            OutlinedTextField(
                state = policy.rawNote,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.policy_raw_note)) },
                inputTransformation = InputTransformation.maxLength(MAX_TEXT_LENGTH),
                placeholder = { Text(stringResource(R.string.policy_raw_note_hint)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            HorizontalDivider()
            Text(stringResource(R.string.policy_live_check), style = MaterialTheme.typography.labelLarge)
            LiveCheck(form, policy, now)
        }
    }
}

/** 정책을 적는 즉시 현재 비밀번호를 검사한다. 검사용 사본은 바로 지운다. */
@Composable
private fun LiveCheck(form: EntryForm, policy: PolicyForm, now: Long) {
    val draft = if (policy.isValid) policy.toPolicy() else null
    val report = draft?.let { p ->
        val chars = (form.passwordText ?: "").copyToCharArray()
        try {
            PasswordPolicyEvaluator.evaluate(chars, p, form.passwordUpdatedAtForCheck(now), now)
        } finally {
            chars.zeroize()
        }
    } ?: PolicyReport.NotConfigured
    PolicyVerdict(draft, report)
}

@Composable
private fun NumberField(state: TextFieldState, @StringRes label: Int, modifier: Modifier, extraError: Boolean = false) {
    val invalid = !isPositiveOrEmpty(state)
    OutlinedTextField(
        state = state,
        modifier = modifier,
        label = { Text(stringResource(label)) },
        lineLimits = TextFieldLineLimits.SingleLine,
        isError = invalid || extraError,
        supportingText = if (invalid) { { Text(stringResource(R.string.invalid_positive)) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSelector(@StringRes label: Int, value: CharClassRule, onChange: (CharClassRule) -> Unit) {
    val options = listOf(CharClassRule.UNKNOWN, CharClassRule.REQUIRED, CharClassRule.ALLOWED, CharClassRule.FORBIDDEN)
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, rule ->
                SegmentedButton(
                    selected = value == rule,
                    onClick = { onChange(rule) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(ruleText(rule)) }
            }
        }
    }
}
