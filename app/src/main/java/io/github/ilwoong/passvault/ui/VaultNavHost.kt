package io.github.ilwoong.passvault.ui

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.ui.common.BiometricEnroller
import io.github.ilwoong.passvault.ui.detail.EntryDetailRoute
import io.github.ilwoong.passvault.ui.edit.EntryEditRoute
import io.github.ilwoong.passvault.ui.list.EntryListRoute
import io.github.ilwoong.passvault.ui.onboarding.WelcomeBiometricRoute
import io.github.ilwoong.passvault.ui.settings.ChangePasswordRoute
import io.github.ilwoong.passvault.ui.settings.SettingsRoute
import javax.inject.Inject

/** 금고 분기의 시작 화면을 한 번만 정한다. */
@HiltViewModel
class VaultHostViewModel @Inject constructor(session: SessionManager, enroller: BiometricEnroller) : ViewModel() {
    /** UX-01 4 단계: 방금 금고를 만들었고 기기가 생체를 지원하면 한 번 제안한다. */
    val startAtWelcome: Boolean = session.consumeJustCreated() && enroller.isAvailable
}

/**
 * 금고 분기 (UX-00). Unlocked 일 때만 컴포지션에 있다.
 * NavController 의 ViewModel 저장소는 BranchScope 의 것이라 잠금 시 함께 비워진다.
 * 경로 인자는 항목 id·타입뿐이며 비밀이 아니다 (UX-00b).
 */
@Composable
fun VaultNavHost(host: VaultHostViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = if (host.startAtWelcome) WELCOME else LIST) {
        composable(WELCOME) {
            WelcomeBiometricRoute(onDone = { nav.navigate(LIST) { popUpTo(WELCOME) { inclusive = true } } })
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
            SettingsRoute(onBack = { nav.popBackStack() }, onChangePassword = { nav.navigate(CHANGE_PASSWORD) })
        }
        composable(CHANGE_PASSWORD) {
            ChangePasswordRoute(onDone = { nav.popBackStack() })
        }
    }
}

private const val WELCOME = "welcome"
private const val LIST = "list"
private const val DETAIL = "detail/{id}"
private const val EDIT = "edit?id={id}&type={type}"
private const val SETTINGS = "settings"
private const val CHANGE_PASSWORD = "settings/password"
