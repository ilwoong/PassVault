package io.github.ilwoong.passvault.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.EntrySummary
import io.github.ilwoong.passvault.data.model.EntryType
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.ui.common.SecureClipboard
import io.github.ilwoong.passvault.ui.common.typeLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EntryListViewModel @Inject constructor(
    private val repo: EntryRepository,
    private val session: SessionManager,
    private val clipboard: SecureClipboard,
) : ViewModel() {

    val query = MutableStateFlow("")
    val type = MutableStateFlow<EntryType?>(null)

    /** null = 아직 읽는 중. entry 테이블만 읽는다 — 비밀 필드가 없다 (NFR-02). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<EntrySummary>?> = combine(query, type, ::Pair)
        .flatMapLatest { (q, t) -> repo.observeSummaries(t, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** DM-03: 시간이 지나 낡은 변경 주기 캐시를 다시 계산한다. 비밀번호를 읽지 않는다. */
    fun refreshRotationDue() {
        viewModelScope.launch { repo.refreshRotationDue() }
    }

    fun toggleFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch { repo.setFavorite(id, favorite) }
    }

    /** LOCK-03 수동 잠금. "끝냈다"는 신호이므로 클립보드도 지운다 (LOCK-04 6). */
    fun lock() {
        session.lock()
        clipboard.clearIfOurs()
    }
}

@Composable
fun EntryListRoute(
    onOpen: (String) -> Unit,
    onAdd: (EntryType) -> Unit,
    onSettings: () -> Unit,
    vm: EntryListViewModel = hiltViewModel(),
) {
    // UX-07: 목록이 시작될 때마다 (해제 직후, 상세에서 돌아올 때, 포그라운드 복귀)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.refreshRotationDue() }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    EntryListScreen(
        entries = entries,
        query = query,
        type = type,
        onQueryChange = { vm.query.value = it },
        onTypeChange = { vm.type.value = it },
        onOpen = onOpen,
        onToggleFavorite = vm::toggleFavorite,
        onAdd = onAdd,
        onLock = vm::lock,
        onSettings = onSettings,
    )
}

/** UX-04 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryListScreen(
    entries: List<EntrySummary>?,
    query: String,
    type: EntryType?,
    onQueryChange: (String) -> Unit,
    onTypeChange: (EntryType?) -> Unit,
    onOpen: (String) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
    onAdd: (EntryType) -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit = {},
) {
    var choosingType by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                    IconButton(onClick = onLock) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.cd_lock))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { choosingType = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_entry))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                },
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = type == null, onClick = { onTypeChange(null) }, label = { Text(stringResource(R.string.type_all)) })
                for (t in EntryType.entries) {
                    FilterChip(selected = type == t, onClick = { onTypeChange(t) }, label = { Text(typeLabel(t)) })
                }
            }
            when {
                entries == null -> Unit
                entries.isEmpty() -> Text(
                    stringResource(if (query.isEmpty() && type == null) R.string.empty_vault else R.string.empty_search),
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { e ->
                        EntryRow(e, onOpen, onToggleFavorite)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (choosingType) {
        AlertDialog(
            onDismissRequest = { choosingType = false },
            title = { Text(stringResource(R.string.choose_type_title)) },
            text = {
                Column {
                    for (t in EntryType.entries) {
                        TextButton(onClick = { choosingType = false; onAdd(t) }, modifier = Modifier.fillMaxWidth()) {
                            Text(typeLabel(t))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingType = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EntryRow(e: EntrySummary, onOpen: (String) -> Unit, onToggleFavorite: (String, Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onOpen(e.id) },
        headlineContent = { Text(e.title) },
        supportingContent = if (e.subtitle != null || e.hasPolicyViolation || e.hasRotationDue) {
            {
                Column {
                    e.subtitle?.let { Text(it) }
                    // 캐시 컬럼만 읽는다 — 목록에서 비밀번호를 복호화하지 않는다 (DM-03)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (e.hasPolicyViolation) {
                            Badge(stringResource(R.string.badge_violation), MaterialTheme.colorScheme.errorContainer)
                        }
                        if (e.hasRotationDue) {
                            Badge(stringResource(R.string.badge_rotation), MaterialTheme.colorScheme.tertiaryContainer)
                        }
                    }
                }
            }
        } else {
            null
        },
        leadingContent = { TypeBadge(e.type) },
        trailingContent = {
            IconToggleButton(checked = e.isFavorite, onCheckedChange = { onToggleFavorite(e.id, it) }) {
                Icon(
                    if (e.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(if (e.isFavorite) R.string.cd_favorite_on else R.string.cd_favorite_off),
                )
            }
        },
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun TypeBadge(type: EntryType) {
    val label = typeLabel(type)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(40.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label.take(1), style = MaterialTheme.typography.titleMedium) }
    }
}
