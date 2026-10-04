package io.github.ilwoong.passvault.ui.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.AutoLock
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

enum class ExportStep { REAUTH, PASSWORD, SAME_AS_MASTER, PICK_FILE, WRITING, DONE }

@HiltViewModel
class ExportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val repo: EntryRepository,
    private val codec: BackupCodec,
    private val autoLock: AutoLock,
) : ViewModel() {
    var step by mutableStateOf(ExportStep.REAUTH)
        private set
    var reauthMessage by mutableStateOf<UnlockMessage?>(null)
        private set
    var lockoutMs by mutableLongStateOf(0L)
        private set
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<BackupError?>(null)
        private set
    var doneCount by mutableStateOf(0)
        private set
    var doneName by mutableStateOf("")
        private set

    /** 파일 선택기를 기다리는 동안만 들고 있다. 쓰고 나면 지운다. */
    private var backupPassword: CharArray? = null

    /** BK-03 1 단계 (UX-03) */
    fun reauthenticate(password: CharArray) {
        viewModelScope.launch {
            val outcome = try {
                session.reauthenticate(password)
            } finally {
                password.zeroize()
            }
            when (outcome) {
                UnlockOutcome.Success -> step = ExportStep.PASSWORD
                UnlockOutcome.WrongPassword -> reauthMessage = UnlockMessage.WRONG_PASSWORD
                is UnlockOutcome.LockedOut -> lockoutMs = outcome.remainingMs
                else -> reauthMessage = UnlockMessage.CANNOT_OPEN
            }
        }
    }

    /** BK-03 2 단계. 마스터와 같으면 경고한다 (실패 횟수에 넣지 않는 비교). */
    fun setBackupPassword(password: CharArray) {
        working = true
        viewModelScope.launch {
            val same = session.matchesMasterPassword(password)
            replacePassword(password)
            working = false
            step = if (same) ExportStep.SAME_AS_MASTER else ExportStep.PICK_FILE
        }
    }

    fun useAnyway() {
        step = ExportStep.PICK_FILE
    }

    fun reenter() {
        replacePassword(null)
        step = ExportStep.PASSWORD
    }

    /** LOCK-03 예외: 우리가 띄운 파일 선택기 동안 백그라운드 즉시 잠금을 보류한다. */
    fun pickerOpening() = autoLock.onExternalPickerOpening()

    /** BK-03 3~6 단계. */
    fun onFileChosen(uri: Uri?) {
        autoLock.onExternalPickerClosed()
        if (uri == null) return
        val password = backupPassword ?: return
        step = ExportStep.WRITING
        error = null
        viewModelScope.launch {
            try {
                val entries = repo.exportAll()
                val bytes = withContext(Dispatchers.Default) {
                    codec.encode(entries, password, appVersion(), System.currentTimeMillis())
                }
                withContext(Dispatchers.IO) { BackupFiles.write(context, uri, bytes) }
                doneCount = entries.size
                doneName = withContext(Dispatchers.IO) { BackupFiles.displayName(context, uri) }
                replacePassword(null)
                step = ExportStep.DONE
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { BackupFiles.deleteQuietly(context, uri) }
                error = BackupError.WRITE_FAILED
                step = ExportStep.PICK_FILE
            }
        }
    }

    override fun onCleared() {
        replacePassword(null)
        autoLock.onExternalPickerClosed()
    }

    private fun replacePassword(p: CharArray?) {
        backupPassword?.zeroize()
        backupPassword = p
    }

    private fun appVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportRoute(onDone: () -> Unit, vm: ExportViewModel = hiltViewModel()) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        vm.onFileChosen(it)
    }
    val choose = {
        vm.pickerOpening()
        launcher.launch(BackupFiles.suggestedName(System.currentTimeMillis()))
    }
    val password = remember { TextFieldState() }
    val confirm = remember { TextFieldState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_export)) },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = vm.step != ExportStep.WRITING) {
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
            when (vm.step) {
                ExportStep.REAUTH -> Unit
                ExportStep.PASSWORD, ExportStep.SAME_AS_MASTER -> {
                    Text(stringResource(R.string.backup_password_body))
                    val ok = NewPasswordFields(password, confirm, R.string.label_backup_password, R.string.label_confirm_backup_password)
                    if (vm.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(
                        enabled = ok && !vm.working,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            vm.setBackupPassword(password.text.copyToCharArray())
                            password.clearText()
                            confirm.clearText()
                        },
                    ) { Text(stringResource(R.string.action_next)) }
                }
                ExportStep.PICK_FILE -> {
                    vm.error?.let { ErrorLine(backupErrorText(it)) }
                    Button(onClick = choose, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_choose_location))
                    }
                }
                ExportStep.WRITING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.backup_writing))
                }
                ExportStep.DONE -> {
                    Text(stringResource(R.string.backup_done, vm.doneCount, vm.doneName))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_confirm)) }
                }
            }
        }
    }

    if (vm.step == ExportStep.REAUTH) ReauthDialog(vm.reauthMessage, vm.lockoutMs, vm::reauthenticate, onDone)
    if (vm.step == ExportStep.SAME_AS_MASTER) {
        AlertDialog(
            onDismissRequest = vm::reenter,
            title = { Text(stringResource(R.string.backup_same_as_master_title)) },
            text = { Text(stringResource(R.string.backup_same_as_master_body)) },
            confirmButton = { TextButton(onClick = vm::useAnyway) { Text(stringResource(R.string.action_use_anyway)) } },
            dismissButton = { TextButton(onClick = vm::reenter) { Text(stringResource(R.string.action_change_it)) } },
        )
    }
}
