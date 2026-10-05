package io.github.ilwoong.passvault.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.ilwoong.passvault.security.AutoLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 백업용 시스템 파일 선택기(SAF)의 결과를 받아 두는 곳 (LOCK-03 의 SAF 예외, UX-00 의 예외).
 *
 * 선택기가 떠 있는 동안이나 돌아오는 순간에 금고가 잠길 수 있다. 그때 금고 화면과 ViewModel 은 이미 비워졌으므로
 * (LOCK-04 4 단계) 결과를 프로세스 수명의 이 자리에 받아 두고, 해제한 뒤 백업 화면이 가져가 이어간다.
 * 담는 것은 문서 위치뿐이다 — 비밀이 아니다. 디스크에 저장하지 않는다. 메인 스레드에서만 쓴다.
 */
class BackupFilePicker(private val autoLock: AutoLock) {

    enum class Purpose { EXPORT, IMPORT }

    data class Picked(val purpose: Purpose, val uri: Uri)

    private val _picked = MutableStateFlow<Picked?>(null)
    val picked: StateFlow<Picked?> = _picked.asStateFlow()

    /** 선택기를 띄우기 직전에 부른다. 떠 있는 동안 백그라운드 즉시 잠금을 보류한다. */
    fun opening() = autoLock.onExternalPickerOpening()

    /** 선택기가 닫혔다. 취소했으면 [uri] 가 null 이다. */
    fun onResult(purpose: Purpose, uri: Uri?) {
        autoLock.onExternalPickerResult()
        if (uri != null) _picked.value = Picked(purpose, uri)
    }

    /** [purpose] 의 결과가 와 있으면 꺼내 간다. */
    fun take(purpose: Purpose): Uri? {
        val p = _picked.value?.takeIf { it.purpose == purpose } ?: return null
        _picked.value = null
        return p.uri
    }

    /** 결과를 기다리던 화면이 사라졌다 (잠금·화면 이탈). 보류가 남지 않게 한다. */
    fun abandoned() = autoLock.onExternalPickerClosed()
}

/** 선택기를 띄우는 손잡이. [ProvideBackupFilePicker] 안에서 [LocalBackupFilePicker] 로 얻는다. */
class BackupFilePickerLauncher(
    val chooseExportLocation: (suggestedName: String) -> Unit,
    val chooseImportFile: () -> Unit,
)

val LocalBackupFilePicker = staticCompositionLocalOf<BackupFilePickerLauncher> { error("ProvideBackupFilePicker 밖이다") }

/**
 * 선택기의 결과 수신을 금고 분기 밖에 등록한다 (UX-00 의 예외). 금고 화면 안에 등록하면, 잠겨서 그 화면이
 * 사라진 뒤에 온 결과를 받을 곳이 없다.
 */
@Composable
fun ProvideBackupFilePicker(picker: BackupFilePicker, content: @Composable () -> Unit) {
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        picker.onResult(BackupFilePicker.Purpose.EXPORT, it)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        picker.onResult(BackupFilePicker.Purpose.IMPORT, it)
    }
    val launcher = remember(picker, create, open) {
        BackupFilePickerLauncher(
            chooseExportLocation = {
                picker.opening()
                create.launch(it)
            },
            chooseImportFile = {
                picker.opening()
                open.launch(arrayOf("*/*"))
            },
        )
    }
    CompositionLocalProvider(LocalBackupFilePicker provides launcher, content = content)
}
