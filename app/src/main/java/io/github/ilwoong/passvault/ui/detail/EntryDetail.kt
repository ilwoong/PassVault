package io.github.ilwoong.passvault.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.data.policy.PasswordPolicyEvaluator
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.common.PolicyVerdict
import io.github.ilwoong.passvault.ui.common.SecureClipboard
import io.github.ilwoong.passvault.ui.common.policySummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val entry: Entry) : DetailState
    data object Gone : DetailState
}

@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: EntryRepository,
    private val clipboard: SecureClipboard,
) : ViewModel() {
    /** UX-05 안내에 쓸 자동 삭제 초. 복사 후 갱신된다. */
    var clipboardClearSeconds by mutableStateOf(0)
        private set

    private val id: String = checkNotNull(savedState["id"])

    /** 저장될 때마다 다시 읽는다 (수정 후 돌아왔을 때). 복호화된 값은 이 ViewModel 수명 동안만 있다. */
    val state: StateFlow<DetailState> = repo.observe(id)
        .map { if (it == null) DetailState.Gone else DetailState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    fun copy(value: String) {
        clipboardClearSeconds = clipboard.copy(value)
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repo.delete(id)
            onDeleted()
        }
    }
}

@Composable
fun EntryDetailRoute(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    vm: EntryDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        DetailState.Loading -> Unit
        DetailState.Gone -> LaunchedEffect(Unit) { onBack() }
        is DetailState.Loaded -> EntryDetailScreen(
            entry = s.entry,
            onBack = onBack,
            onEdit = { onEdit(s.entry.id) },
            onDelete = { vm.delete(onBack) },
            onCopy = vm::copy,
            clipboardClearSeconds = { vm.clipboardClearSeconds },
        )
    }
}

/** 한 줄. 비밀이면 기본 마스킹이다. */
data class DetailField(@StringRes val label: Int, val value: String, val secret: Boolean)

fun detailFields(content: EntryContent): List<DetailField> {
    val fields = when (content) {
        is EntryContent.Login -> listOf(
            DetailField(R.string.field_username, content.username.orEmpty(), secret = false),
            DetailField(R.string.field_password, content.password.orEmpty(), secret = true),
            // URL 은 텍스트로만 보여준다. 외부 브라우저로 열지 않는다 (UX-05)
            DetailField(R.string.field_url, content.url.orEmpty(), secret = false),
            DetailField(R.string.field_memo, content.memo.orEmpty(), secret = true),
        )
        is EntryContent.Note -> listOf(DetailField(R.string.field_body, content.body, secret = true))
        is EntryContent.Card -> listOf(
            DetailField(R.string.field_cardholder, content.cardholderName.orEmpty(), secret = false),
            DetailField(R.string.field_card_number, content.number.orEmpty(), secret = true),
            DetailField(R.string.field_brand, content.brand.orEmpty(), secret = false),
            DetailField(R.string.field_expiry, expiry(content.expiryMonth, content.expiryYear), secret = false),
            DetailField(R.string.field_cvc, content.cvc.orEmpty(), secret = true),
            DetailField(R.string.field_pin, content.pin.orEmpty(), secret = true),
            DetailField(R.string.field_memo, content.memo.orEmpty(), secret = true),
        )
        is EntryContent.Identity -> listOf(
            DetailField(R.string.field_doc_type, content.docType.orEmpty(), secret = false),
            DetailField(R.string.field_full_name, content.fullName.orEmpty(), secret = false),
            DetailField(R.string.field_doc_number, content.docNumber.orEmpty(), secret = true),
            DetailField(R.string.field_issuer, content.issuer.orEmpty(), secret = false),
            DetailField(R.string.field_issued_date, content.issuedDate.orEmpty(), secret = false),
            DetailField(R.string.field_expiry_date, content.expiryDate.orEmpty(), secret = false),
            DetailField(R.string.field_memo, content.memo.orEmpty(), secret = true),
        )
    }
    return fields.filter { it.value.isNotEmpty() }
}

private fun expiry(month: Int?, year: Int?): String = when {
    month != null && year != null -> "%02d/%d".format(month, year)
    month != null -> "%02d".format(month)
    else -> year?.toString().orEmpty()
}

/** UX-05 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    entry: Entry,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String) -> Unit,
    nowEpochMs: Long = System.currentTimeMillis(),
    clipboardClearSeconds: () -> Int = { 0 },
) {
    // 표시 상태는 저장하지 않는다 — 화면을 떠나면 다시 가려진다 (UX-05, UX-00b)
    val revealed = remember { mutableStateMapOf<Int, Boolean>() }
    var confirmingDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.copied)
    val copiedWithClear = stringResource(R.string.copied_with_clear)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_edit))
                    }
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            for (field in detailFields(entry.content)) {
                FieldRow(
                    field = field,
                    revealed = revealed[field.label] == true,
                    onToggle = { revealed[field.label] = revealed[field.label] != true },
                    onCopy = {
                        onCopy(field.value)
                        val seconds = clipboardClearSeconds()
                        val text = if (seconds > 0) copiedWithClear.format(seconds) else copiedText
                        scope.launch { snackbar.showSnackbar(text) }
                    },
                )
                HorizontalDivider()
            }
            (entry.content as? EntryContent.Login)?.let { PolicyPanel(entry, it, nowEpochMs, onRecord = onEdit) }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun FieldRow(field: DetailField, revealed: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val masked = field.secret && !revealed
    val stateText = stringResource(if (masked) R.string.state_hidden else R.string.state_shown)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(field.label), style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                // 고정 길이로 가린다 — 길이도 정보다
                text = if (masked) stringResource(R.string.masked) else field.value,
                modifier = Modifier
                    .weight(1f)
                    .then(if (field.secret) Modifier.semantics { stateDescription = stateText } else Modifier),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (field.secret) {
                TextButton(onClick = onToggle) {
                    Text(stringResource(if (revealed) R.string.action_hide else R.string.action_show))
                }
            }
            TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy)) }
        }
    }
}

/** UX-05: 로그인 항목의 정책 요약·원문·판정과 비밀번호 경과일. */
@Composable
private fun PolicyPanel(entry: Entry, login: EntryContent.Login, now: Long, onRecord: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.policy_section_title), style = MaterialTheme.typography.titleSmall)
        entry.passwordUpdatedAtEpochMs?.let {
            Text(stringResource(R.string.policy_days_since_change, ((now - it) / DAY_MS).toInt().coerceAtLeast(0)))
        }
        val policy = login.policy
        if (policy == null) {
            // DM-10 NotConfigured: 아무 판정도 하지 않고 기록을 유도한다
            TextButton(onClick = onRecord) { Text(stringResource(R.string.policy_record_prompt)) }
            return@Column
        }
        for (line in policySummary(policy)) Text("• $line")
        // 원문은 검사하지 않고 그대로 보여준다 (DM-10)
        policy.rawNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val report = remember(login, entry.passwordUpdatedAtEpochMs, now) {
            val chars = login.password.orEmpty().toCharArray()
            try {
                PasswordPolicyEvaluator.evaluate(chars, policy, entry.passwordUpdatedAtEpochMs, now)
            } finally {
                chars.zeroize()
            }
        }
        PolicyVerdict(policy, report)
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
