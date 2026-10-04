package io.github.ilwoong.passvault.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.backup.BackupCodec
import io.github.ilwoong.passvault.backup.BackupResult
import io.github.ilwoong.passvault.ui.onboarding.MIN_MASTER_PASSWORD_LENGTH

/** BK-06: 사용자가 다음 행동을 정할 수 있을 만큼만 구분한다. */
enum class BackupError { NOT_BACKUP, NEWER_VERSION, TOO_LARGE, WRONG_PASSWORD, MALFORMED, READ_FAILED, WRITE_FAILED }

fun BackupResult.toError(): BackupError? = when (this) {
    is BackupResult.Success -> null
    BackupResult.NotBackup -> BackupError.NOT_BACKUP
    BackupResult.NewerVersion -> BackupError.NEWER_VERSION
    BackupResult.TooLarge -> BackupError.TOO_LARGE
    BackupResult.WrongPasswordOrCorrupt -> BackupError.WRONG_PASSWORD
    BackupResult.Malformed -> BackupError.MALFORMED
}

@Composable
fun backupErrorText(e: BackupError): String = stringResource(
    when (e) {
        BackupError.NOT_BACKUP -> R.string.err_not_backup
        BackupError.NEWER_VERSION -> R.string.err_newer_version
        BackupError.TOO_LARGE -> R.string.err_too_large
        BackupError.WRONG_PASSWORD -> R.string.err_wrong_backup_password
        BackupError.MALFORMED -> R.string.err_malformed
        BackupError.READ_FAILED -> R.string.err_read_failed
        BackupError.WRITE_FAILED -> R.string.backup_write_failed
    },
)

/** 새 비밀번호 + 확인 입력. 저장되지 않는 상태만 받는다 (UX-00b). 유효하면 true. */
@Composable
fun NewPasswordFields(password: TextFieldState, confirm: TextFieldState, label: Int, confirmLabel: Int): Boolean {
    val longEnough = password.text.length >= MIN_MASTER_PASSWORD_LENGTH
    val matches = password.text.contentEquals(confirm.text)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedSecureTextField(
            state = password,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(label)) },
            supportingText = {
                if (!longEnough) Text(stringResource(R.string.password_too_short, MIN_MASTER_PASSWORD_LENGTH))
            },
        )
        OutlinedSecureTextField(
            state = confirm,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(confirmLabel)) },
            isError = confirm.text.isNotEmpty() && !matches,
            supportingText = {
                if (confirm.text.isNotEmpty() && !matches) Text(stringResource(R.string.password_mismatch))
            },
        )
    }
    return longEnough && matches
}

@Composable
fun ErrorLine(text: String) = Text(text, color = MaterialTheme.colorScheme.error)

/** SAF 로 고른 문서 입출력. 평문을 임시 파일로 쓰지 않는다 — 암호문만 오간다 (BK-03). */
object BackupFiles {

    /** 크기 상한을 넘으면 null. */
    fun read(context: Context, uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > BackupCodec.MAX_FILE_BYTES) return null
            }
            out.toByteArray()
        }

    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        val out = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "열 수 없다" }
        out.use { it.write(bytes) }
    }

    /** BK-03: 실패하면 부분 기록된 파일을 지운다. */
    fun deleteQuietly(context: Context, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** BK-01: passvault-backup-YYYYMMDD-HHmm.pvault */
    fun suggestedName(nowEpochMs: Long): String =
        "passvault-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date(nowEpochMs)) + ".pvault"
}
