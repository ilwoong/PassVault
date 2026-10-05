package io.github.ilwoong.passvault.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TST-14 (잠금 시 화면 전환), UX-00: 저장된 백스택은 같은 해제 구간 안에서만 복원된다.
 * Activity 저장 상태의 복원(화면 회전, 프로세스 재시작)을 [StateRestorationTester] 로 흉내 낸다.
 */
@RunWith(AndroidJUnit4::class)
class VaultSessionScopeTest {

    @get:Rule
    val compose = createComposeRule()

    private var sessionKey = 1

    @Composable
    private fun TwoScreens() {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = "list") {
            composable("list") { Button(onClick = { nav.navigate("detail") }) { Text("목록") } }
            composable("detail") { Text("상세") }
        }
    }

    private fun openDetailThenRestore(): StateRestorationTester {
        val tester = StateRestorationTester(compose)
        tester.setContent { VaultSessionScope(sessionKey) { TwoScreens() } }
        compose.onNodeWithText("목록").performClick()
        compose.onNodeWithText("상세").assertIsDisplayed()
        return tester
    }

    @Test
    fun rotationWithinOneUnlockKeepsTheScreen() {
        openDetailThenRestore().emulateSavedInstanceStateRestore()
        compose.onNodeWithText("상세").assertIsDisplayed()
    }

    @Test
    fun stateSavedInAnEarlierUnlockIsNotRestored() {
        val tester = openDetailThenRestore()
        sessionKey = 2 // 잠겼다가 다시 해제됐다, 또는 프로세스가 새로 떴다
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("목록").assertIsDisplayed()
        compose.onAllNodesWithText("상세").assertCountEquals(0)
    }
}
