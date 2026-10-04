package io.github.ilwoong.passvault.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.ilwoong.passvault.ui.detail.EntryDetailRoute
import io.github.ilwoong.passvault.ui.edit.EntryEditRoute
import io.github.ilwoong.passvault.ui.list.EntryListRoute

/**
 * 금고 분기 (UX-00). Unlocked 일 때만 컴포지션에 있다.
 * NavController 의 ViewModel 저장소는 BranchScope 의 것이라 잠금 시 함께 비워진다.
 * 경로 인자는 항목 id·타입뿐이며 비밀이 아니다 (UX-00b).
 */
@Composable
fun VaultNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = LIST) {
        composable(LIST) {
            EntryListRoute(
                onOpen = { nav.navigate("detail/$it") },
                onAdd = { nav.navigate("edit?type=${it.name}") },
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
    }
}

private const val LIST = "list"
private const val DETAIL = "detail/{id}"
private const val EDIT = "edit?id={id}&type={type}"
