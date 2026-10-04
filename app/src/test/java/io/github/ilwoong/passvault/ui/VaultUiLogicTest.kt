package io.github.ilwoong.passvault.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import io.github.ilwoong.passvault.security.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.repo.escapeLike
import io.github.ilwoong.passvault.ui.detail.detailFields
import io.github.ilwoong.passvault.ui.edit.FieldKind
import io.github.ilwoong.passvault.ui.edit.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class VaultUiLogicTest {

    // --- UX-05: 어떤 필드가 비밀로 가려지는가 ---

    @Test
    fun secretFieldsAreExactlyTheSpecSecretFields() {
        fun secrets(c: EntryContent) = detailFields(c).filter { it.secret }.map { it.label }.toSet()
        assertEquals(
            setOf(R.string.field_password, R.string.field_memo),
            secrets(EntryContent.Login("u", "p", "url", "m")),
        )
        assertEquals(setOf(R.string.field_body), secrets(EntryContent.Note("b")))
        assertEquals(
            setOf(R.string.field_card_number, R.string.field_cvc, R.string.field_pin, R.string.field_memo),
            secrets(EntryContent.Card("n", "4111", "V", 1, 2030, "123", "0000", "m")),
        )
        assertEquals(
            setOf(R.string.field_doc_number, R.string.field_memo),
            secrets(EntryContent.Identity("t", "n", "123", "i", "2020-01-01", "2030-01-01", "m")),
        )
    }

    @Test
    fun emptyFieldsAreNotShown() {
        assertEquals(listOf(R.string.field_password), detailFields(EntryContent.Login(password = "p")).map { it.label })
    }

    // --- UX-06: 입력 검증 ---

    @Test
    fun fieldValidation() {
        assertTrue(validate(FieldKind.MONTH, ""))
        assertTrue(validate(FieldKind.MONTH, "01"))
        assertTrue(validate(FieldKind.MONTH, "12"))
        assertFalse(validate(FieldKind.MONTH, "13"))
        assertFalse(validate(FieldKind.MONTH, "0"))
        assertTrue(validate(FieldKind.YEAR, "2030"))
        assertFalse(validate(FieldKind.YEAR, "30"))
        assertTrue(validate(FieldKind.DATE, "2024-02-29"))
        assertFalse(validate(FieldKind.DATE, "2023-02-29"))
        assertFalse(validate(FieldKind.DATE, "2024/01/01"))
        assertTrue(validate(FieldKind.SECRET, "anything"))
    }

    // --- UX-04 검색 ---

    @Test
    fun likeWildcardsAreEscaped() {
        assertEquals("100\\%", escapeLike("100%"))
        assertEquals("a\\_b", escapeLike("a_b"))
        assertEquals("c:\\\\d", escapeLike("c:\\d"))
        assertEquals("한글", escapeLike("한글"))
    }

    // --- LOCK-04 4 단계 ---

    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = Probe() as T
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchingBranchClearsPreviousBranchViewModels() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val stores = BranchStores(MutableStateFlow(SessionState.Unlocked))
            val vault = ViewModelProvider.create(stores.storeFor("vault"), factory)[Probe::class]

            assertTrue("같은 분기면 그대로", stores.storeFor("vault") === stores.storeFor("vault"))
            assertFalse(vault.cleared)

            stores.storeFor("unlock") // 분기 전환
            assertTrue("분기가 바뀌면 이전 분기 ViewModel 이 비워져야 한다", vault.cleared)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lockClearsVaultBranchWithoutWaitingForBranchSwitch() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val state = MutableStateFlow<SessionState>(SessionState.Unlocked)
            val stores = BranchStores(state)
            val vault = ViewModelProvider.create(stores.storeFor(VAULT_BRANCH), factory)[Probe::class]

            state.value = SessionState.Locked // 컴포지션(storeFor)은 아직 돌지 않았다
            assertTrue(vault.cleared)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
