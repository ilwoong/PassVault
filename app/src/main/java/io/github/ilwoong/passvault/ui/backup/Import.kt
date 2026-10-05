package io.github.ilwoong.passvault.ui.backup

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.backup.BackupCodec
import io.github.ilwoong.passvault.backup.BackupResult
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.UnlockOutcome
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.copyToCharArray
import io.github.ilwoong.passvault.ui.settings.ReauthDialog
import io.github.ilwoong.passvault.ui.unlock.UnlockMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ImportStep { PICK_FILE, REAUTH, PASSWORD, DECRYPTING, CONFIRM, REPLACING, DONE }

@HiltViewModel
class ImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val repo: EntryRepository,
    private val codec: BackupCodec,
    private val picker: BackupFilePicker,
) : ViewModel() {
    var step by mutableStateOf(ImportStep.PICK_FILE)
        private set
    var reauthMessage by mutableStateOf<UnlockMessage?>(null)
        private set
    var lockoutMs by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<BackupError?>(null)
        private set
    var count by mutableStateOf(0)
        private set

    private var fileBytes: ByteArray? = null

    /** 복호화된 항목. 확인을 기다리는 동안만 들고 있다. */
    private var decoded: List<Entry>? = null

    /** 이 화면에서 이미 재인증했다. 파일을 다시 고를 때 또 묻지 않는다. */
    private var reauthenticated = false

    init {
        // BK-04 1 단계의 결과. 고르는 사이에 잠겼다면 해제된 뒤에 만들어진 이 화면이 이어받는다 (LOCK-03)
        viewModelScope.launch {
            picker.picked.collect { picker.take(BackupFilePicker.Purpose.IMPORT)?.let(::onFileChosen) }
        }
    }

    /** BK-04 3 단계 (UX-03) — 전량 교체는 파괴적이다 */
    fun reauthenticate(password: CharArray) {
        viewModelScope.launch {
            val outcome = try {
                session.reauthenticate(password)
            } finally {
                password.zeroize()
            }
            when (outcome) {
                UnlockOutcome.Success -> {
                    reauthenticated = true
                    step = ImportStep.PASSWORD
                }
                UnlockOutcome.WrongPassword -> reauthMessage = UnlockMessage.WRONG_PASSWORD
                is UnlockOutcome.LockedOut -> lockoutMs = outcome.remainingMs
                else -> reauthMessage = UnlockMessage.CANNOT_OPEN
            }
        }
    }

    /** BK-04 1·2 단계 */
    private fun onFileChosen(uri: Uri) {
        error = null
        viewModelScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { BackupFiles.read(context, uri) }
            } catch (e: Exception) {
                error = BackupError.READ_FAILED
                return@launch
            }
            if (bytes == null) {
                error = BackupError.TOO_LARGE
                return@launch
            }
            codec.inspect(bytes)?.let {
                error = it.toError()
                return@launch
            }
            fileBytes = bytes
            step = if (reauthenticated) ImportStep.PASSWORD else ImportStep.REAUTH
        }
    }

    /** BK-04 4·5 단계 */
    fun decrypt(password: CharArray) {
        val bytes = fileBytes ?: return
        step = ImportStep.DECRYPTING
        error = null
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.Default) { codec.decode(bytes, password) }
            } finally {
                password.zeroize()
            }
            if (result is BackupResult.Success) {
                decoded = result.entries
                count = result.entries.size
                step = ImportStep.CONFIRM
            } else {
                error = result.toError()
                step = if (result == BackupResult.WrongPasswordOrCorrupt) ImportStep.PASSWORD else ImportStep.PICK_FILE
            }
        }
    }

    fun cancelConfirm() {
        decoded = null
        step = ImportStep.PICK_FILE
    }

    /** BK-04 7 단계: 단일 트랜잭션. 실패하면 기존 데이터가 그대로 남는다. */
    fun replace() {
        val entries = decoded ?: return
        step = ImportStep.REPLACING
        viewModelScope.launch {
            try {
                repo.replaceAll(entries)
                step = ImportStep.DONE
            } catch (e: Exception) {
                error = BackupError.MALFORMED
                step = ImportStep.PICK_FILE
            } finally {
                decoded = null
                fileBytes = null
            }
        }
    }

    override fun onCleared() {
        decoded = null
        fileBytes = null
        picker.abandoned()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportRoute(onDone: () -> Unit, vm: ImportViewModel = hiltViewModel()) {
    val choose = LocalBackupFilePicker.current.chooseImportFile
    val password = remember { TextFieldState() }
    val busy = vm.step == ImportStep.DECRYPTING || vm.step == ImportStep.REPLACING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_import)) },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.error?.let { ErrorLine(backupErrorText(it)) }
            when (vm.step) {
                ImportStep.REAUTH -> Unit
                ImportStep.PICK_FILE -> Button(onClick = choose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_choose_file))
                }
                ImportStep.PASSWORD -> BackupPasswordInput(password) { vm.decrypt(it) }
                ImportStep.DECRYPTING, ImportStep.REPLACING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (vm.step == ImportStep.DECRYPTING) R.string.backup_decrypting else R.string.import_replacing))
                }
                ImportStep.CONFIRM -> Unit
                ImportStep.DONE -> {
                    Text(stringResource(R.string.import_done, vm.count))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_confirm)) }
                }
            }
        }
    }

    if (vm.step == ImportStep.REAUTH) ReauthDialog(vm.reauthMessage, vm.lockoutMs, vm::reauthenticate, onDone)
    if (vm.step == ImportStep.CONFIRM) {
        // BK-04 6 단계: 요약을 보여 주고 확인받는다
        AlertDialog(
            onDismissRequest = vm::cancelConfirm,
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(R.string.import_confirm_body, vm.count)) },
            confirmButton = {
                TextButton(onClick = vm::replace) {
                    Text(stringResource(R.string.action_replace), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelConfirm) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** 백업 비밀번호 한 칸. 저장되지 않는 상태만 받는다 (UX-00b). */
@Composable
fun BackupPasswordInput(state: TextFieldState, onSubmit: (CharArray) -> Unit) {
    OutlinedSecureTextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.label_backup_password)) },
    )
    Button(
        enabled = state.text.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onSubmit(state.text.copyToCharArray())
            state.clearText()
        },
    ) { Text(stringResource(R.string.action_next)) }
}
