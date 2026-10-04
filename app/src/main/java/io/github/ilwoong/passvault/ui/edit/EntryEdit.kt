package io.github.ilwoong.passvault.ui.edit

import androidx.activity.compose.BackHandler
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
