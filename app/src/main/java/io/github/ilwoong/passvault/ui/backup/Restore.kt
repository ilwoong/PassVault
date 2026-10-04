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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.backup.BackupCodec
import io.github.ilwoong.passvault.backup.BackupResult
import io.github.ilwoong.passvault.data.db.VaultDatabaseHolder
import io.github.ilwoong.passvault.data.model.Entry
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.zeroize
import io.github.ilwoong.passvault.ui.copyToCharArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class RestoreStep { PICK_FILE, PASSWORD, DECRYPTING, NEW_PASSWORD, CONFIRM, RESTORING }

/**
 * BK-05: 금고를 열 수 없을 때 백업 파일로 새 금고를 만든다. 해제 분기에 있다 (UX-00).
 * 백업 복호화가 성공한 **뒤에만** 기존 금고를 폐기한다.
 */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val holder: VaultDatabaseHolder,
    private val codec: BackupCodec,
) : ViewModel() {
    var step by mutableStateOf(RestoreStep.PICK_FILE)
        private set
    var error by mutableStateOf<BackupError?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var count by mutableStateOf(0)
        private set

    private var fileBytes: ByteArray? = null
    private var decoded: List<Entry>? = null
    private var newPassword: CharArray? = null

    fun onFileChosen(uri: Uri?) {
        if (uri == null) return
        error = null
        viewModelScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { BackupFiles.read(context, uri) }
            } catch (e: Exception) {
                error = BackupError.READ_FAILED
                return@launch
            } ?: run {
                error = BackupError.TOO_LARGE
                return@launch
            }
            codec.inspect(bytes)?.let {
                error = it.toError()
                return@launch
            }
            fileBytes = bytes
            step = RestoreStep.PASSWORD
        }
    }

    /** BK-05 1 단계: 여기까지 성공해야 다음으로 간다. */
    fun decrypt(password: CharArray) {
        val bytes = fileBytes ?: return
        step = RestoreStep.DECRYPTING
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
                step = RestoreStep.NEW_PASSWORD
            } else {
                error = result.toError()
                step = if (result == BackupResult.WrongPasswordOrCorrupt) RestoreStep.PASSWORD else RestoreStep.PICK_FILE
            }
        }
    }

    /** BK-05 2 단계. 소유권을 넘겨받는다. */
    fun setNewPassword(password: CharArray) {
        newPassword?.zeroize()
        newPassword = password
        step = RestoreStep.CONFIRM
    }

    fun cancelConfirm() {
        newPassword?.zeroize()
        newPassword = null
        step = RestoreStep.NEW_PASSWORD
    }

    /** BK-05 3·4 단계. 성공하면 세션이 Unlocked 가 되어 이 화면이 사라진다. */
    fun restore() {
        val entries = decoded ?: return
        val password = newPassword ?: return
        step = RestoreStep.RESTORING
        failed = false
        viewModelScope.launch {
            try {
                session.recreateVault(password) {
                    EntryRepository(holder.requireDatabase().dao()).replaceAll(entries)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                step = RestoreStep.NEW_PASSWORD
            } finally {
                password.zeroize()
                newPassword = null
            }
        }
    }

    override fun onCleared() {
        decoded = null
        fileBytes = null
        newPassword?.zeroize()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreRoute(onCancel: () -> Unit, vm: RestoreViewModel = hiltViewModel()) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onFileChosen(it) }
    val backupPassword = remember { TextFieldState() }
    val newPassword = remember { TextFieldState() }
    val confirm = remember { TextFieldState() }
    val busy = vm.step == RestoreStep.DECRYPTING || vm.step == RestoreStep.RESTORING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !busy) {
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
            if (vm.failed) ErrorLine(stringResource(R.string.restore_failed))
            when (vm.step) {
                RestoreStep.PICK_FILE -> Button(onClick = { launcher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_choose_file))
                }
                RestoreStep.PASSWORD -> BackupPasswordInput(backupPassword) { vm.decrypt(it) }
                RestoreStep.DECRYPTING, RestoreStep.RESTORING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (vm.step == RestoreStep.DECRYPTING) R.string.backup_decrypting else R.string.restoring))
                }
                RestoreStep.NEW_PASSWORD, RestoreStep.CONFIRM -> {
                    Text(stringResource(R.string.restore_new_password_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.restore_new_password_body))
                    val ok = NewPasswordFields(newPassword, confirm, R.string.label_master_password, R.string.label_confirm_password)
                    Button(
                        enabled = ok,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            vm.setNewPassword(newPassword.text.copyToCharArray())
                            newPassword.clearText()
                            confirm.clearText()
                        },
                    ) { Text(stringResource(R.string.action_next)) }
                }
            }
        }
    }

    if (vm.step == RestoreStep.CONFIRM) {
        // BK-05: "기존 금고 폐기"를 명시하고 확인받는다
        AlertDialog(
            onDismissRequest = vm::cancelConfirm,
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_body, vm.count)) },
            confirmButton = {
                TextButton(onClick = vm::restore) {
                    Text(stringResource(R.string.action_restore), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelConfirm) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
