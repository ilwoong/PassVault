package io.github.ilwoong.passvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.ui.backup.BackupFilePicker
import io.github.ilwoong.passvault.ui.backup.ExportRoute
import io.github.ilwoong.passvault.ui.backup.ImportRoute
import io.github.ilwoong.passvault.ui.common.BiometricEnroller
import io.github.ilwoong.passvault.ui.detail.EntryDetailRoute
import io.github.ilwoong.passvault.ui.edit.EntryEditRoute
import io.github.ilwoong.passvault.ui.list.EntryListRoute
import io.github.ilwoong.passvault.ui.onboarding.WelcomeBackupScreen
import io.github.ilwoong.passvault.ui.onboarding.WelcomeBiometricRoute
import io.github.ilwoong.passvault.ui.settings.AboutRoute
import io.github.ilwoong.passvault.ui.settings.ChangePasswordRoute
import io.github.ilwoong.passvault.ui.settings.SettingsRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import kotlin.random.Random

/** 금고 분기의 시작 화면을 한 번만 정한다. */
@HiltViewModel
class VaultHostViewModel @Inject constructor(
    session: SessionManager,
    enroller: BiometricEnroller,
    backupFilePicker: BackupFilePicker,
) : ViewModel() {
    private val justCreated = session.consumeJustCreated()

    /** UX-01 4·5 단계: 방금 금고를 만들었으면 생체(지원 기기만) → 백업 안내를 한 번 보여 준다. */
    val start: String = when {
        !justCreated -> LIST
        enroller.isAvailable -> WELCOME
        else -> WELCOME_BACKUP
    }

    /**
     * UX-00: 이 해제 구간의 표식. 금고 분기의 저장된 화면 상태는 표식이 같을 때만 복원된다 ([VaultSessionScope]).
     * 이 ViewModel 은 화면 회전에는 살아남고, 잠기거나 프로세스가 끝나면 새로 만들어진다.
     */
    val sessionKey: Int = Random.nextInt()

    /** LOCK-03: 백업 파일 선택기의 결과가 와 있는 동안, 그 결과를 이어받을 백업 화면. */
    val backupToResume: Flow<String> = backupFilePicker.picked.filterNotNull().map {
        when (it.purpose) {
            BackupFilePicker.Purpose.EXPORT -> BACKUP_EXPORT
            BackupFilePicker.Purpose.IMPORT -> BACKUP_IMPORT
        }
    }
}

/**
 * UX-00: 잠긴 뒤의 해제는 항상 처음 화면에서 시작한다. 저장 상태(`rememberSaveable` — 백스택 포함)의 키에
 * [sessionKey] 를 섞어, 다른 해제 구간에서 저장된 상태가 복원되지 않게 한다.
 *
 * 이것이 없으면 Activity 저장 번들에 남은 백스택이 되살아난다: 프로세스가 죽었다 살아났을 때, 그리고
 * 백그라운드에서 잠긴 뒤 화면 회전 등으로 Activity 가 다시 만들어졌을 때. 같은 해제 구간의 화면 회전은 그대로 복원된다.
 */
@Composable
internal fun VaultSessionScope(sessionKey: Int, content: @Composable () -> Unit) = key(sessionKey) { content() }

/**
 * 금고 분기 (UX-00). Unlocked 일 때만 컴포지션에 있다.
 * NavController 의 ViewModel 저장소는 BranchScope 의 것이라 잠금 시 함께 비워진다.
 * 경로 인자는 항목 id·타입뿐이며 비밀이 아니다 (UX-00b).
 */
@Composable
fun VaultNavHost(host: VaultHostViewModel = hiltViewModel()) = VaultSessionScope(host.sessionKey) { VaultNavGraph(host) }

@Composable
private fun VaultNavGraph(host: VaultHostViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = host.start) {
        composable(WELCOME) {
            WelcomeBiometricRoute(onDone = { nav.navigate(WELCOME_BACKUP) { popUpTo(WELCOME) { inclusive = true } } })
        }
        composable(WELCOME_BACKUP) {
            val toList: () -> Unit = { nav.navigate(LIST) { popUpTo(WELCOME_BACKUP) { inclusive = true } } }
            WelcomeBackupScreen(
                onBackupNow = {
                    toList()
                    nav.navigate(BACKUP_EXPORT)
                },
                onLater = toList,
            )
        }
        composable(LIST) {
            EntryListRoute(
                onOpen = { nav.navigate("detail/$it") },
                onAdd = { nav.navigate("edit?type=${it.name}") },
                onSettings = { nav.navigate(SETTINGS) },
            )
        }
        composable(DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            EntryDetailRoute(
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("edit?id=$it") },
            )
        }
        composable(
            EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            EntryEditRoute(
                onSaved = { id, wasNew ->
                    nav.popBackStack()
                    if (wasNew) nav.navigate("detail/$id")
                },
                onExit = { nav.popBackStack() },
            )
        }
        composable(SETTINGS) {
            SettingsRoute(
                onBack = { nav.popBackStack() },
                onChangePassword = { nav.navigate(CHANGE_PASSWORD) },
                onBackupExport = { nav.navigate(BACKUP_EXPORT) },
                onBackupImport = { nav.navigate(BACKUP_IMPORT) },
                onAbout = { nav.navigate(ABOUT) },
            )
        }
        composable(BACKUP_EXPORT) { ExportRoute(onDone = { nav.popBackStack() }) }
        composable(BACKUP_IMPORT) { ImportRoute(onDone = { nav.popBackStack() }) }
        composable(ABOUT) { AboutRoute(onBack = { nav.popBackStack() }) }
        composable(CHANGE_PASSWORD) {
            ChangePasswordRoute(onDone = { nav.popBackStack() })
        }
    }
    // LOCK-03: 선택기의 결과가 왔는데 그 백업 화면이 맨 위가 아니면 띄운다 — 고르는 사이에 잠겼다가 해제된 경우다
    LaunchedEffect(nav) { host.backupToResume.collect { if (nav.currentDestination?.route != it) nav.navigate(it) } }
}

private const val WELCOME = "welcome"
private const val WELCOME_BACKUP = "welcome/backup"
private const val BACKUP_EXPORT = "backup/export"
private const val BACKUP_IMPORT = "backup/import"
private const val LIST = "list"
private const val DETAIL = "detail/{id}"
private const val EDIT = "edit?id={id}&type={type}"
private const val SETTINGS = "settings"
private const val CHANGE_PASSWORD = "settings/password"
private const val ABOUT = "settings/about"
