package io.github.ilwoong.passvault.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.backup.BackupCodec
import io.github.ilwoong.passvault.backup.BackupResult
import io.github.ilwoong.passvault.data.db.VaultDatabaseHolder
import io.github.ilwoong.passvault.data.model.EntryContent
import io.github.ilwoong.passvault.data.model.EntryDraft
import io.github.ilwoong.passvault.data.repo.EntryRepository
import io.github.ilwoong.passvault.security.AesGcmKeyWrapper
import io.github.ilwoong.passvault.security.Argon2KeyDeriver
import io.github.ilwoong.passvault.security.AutoLock
import io.github.ilwoong.passvault.security.BiometricKeyStore
import io.github.ilwoong.passvault.security.FakeClocks
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.SessionState
import io.github.ilwoong.passvault.security.UnlockOutcome
import io.github.ilwoong.passvault.security.VaultKeyManager
import io.github.ilwoong.passvault.security.VaultMetaStore
import io.github.ilwoong.passvault.ui.backup.BackupFilePicker
import io.github.ilwoong.passvault.ui.backup.BackupFilePicker.Purpose
import io.github.ilwoong.passvault.ui.backup.ExportStep
import io.github.ilwoong.passvault.ui.backup.ExportViewModel
import io.github.ilwoong.passvault.ui.backup.ImportStep
import io.github.ilwoong.passvault.ui.backup.ImportViewModel
import io.github.ilwoong.passvault.ui.common.BiometricEnroller
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * TST-09 (잠금을 넘긴 백업), LOCK-03 의 SAF 예외, BK-03, BK-04 — 실제 Argon2id·SQLCipher 위에서.
 * 선택기는 띄우지 않고, 선택기가 하는 일(빈 문서 생성, 결과 전달)을 직접 한다.
 */
@RunWith(AndroidJUnit4::class)
class BackupAcrossLockTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val codec = BackupCodec(Argon2KeyDeriver()::derive)
    private val dbName = "bal-${System.nanoTime()}.db"
    private val clocks = FakeClocks()
    private lateinit var dir: File
    private lateinit var holder: VaultDatabaseHolder
    private lateinit var session: SessionManager
    private lateinit var autoLock: AutoLock
    private lateinit var picker: BackupFilePicker

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "bal-${System.nanoTime()}").apply { mkdirs() }
        holder = VaultDatabaseHolder(context, dbName)
        session = SessionManager(
            VaultKeyManager(VaultMetaStore(File(dir, "vault_meta")), Argon2KeyDeriver(), AesGcmKeyWrapper(), clocks),
            holder,
        )
        runBlocking {
            session.createVault(MASTER.toCharArray())
            repo().save(EntryDraft(null, "GitHub", false, EntryContent.Note("본문")))
        }
        autoLock = AutoLock(
            clocks = clocks,
            timeoutMs = { 15_000L },
            lockOnBackground = { true },
            lock = { session.lock() },
            lockOnLeave = { session.lock(deferIfBusy = true) },
            clearClipboard = {},
        )
        picker = BackupFilePicker(autoLock)
    }

    @After
    fun tearDown() {
        session.lock()
        dir.deleteRecursively()
        context.deleteDatabase(dbName)
    }

    private fun repo() = EntryRepository(holder.requireDatabase().dao())

    private fun <T : ViewModel> onMain(block: () -> T): T {
        lateinit var vm: T
        instrumentation.runOnMainSync { vm = block() }
        return vm
    }

    /** 금고 분기의 저장소에 담긴 것처럼 만든다. [ViewModelStore.clear] 가 LOCK-04 4 단계다. */
    private fun <T : ViewModel> ViewModelStore.put(type: Class<T>, make: () -> T): T = onMain {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        })[type]
    }

    private fun exportVm(store: ViewModelStore = ViewModelStore()) =
        store.put(ExportViewModel::class.java) { ExportViewModel(context, session, repo(), codec, picker) }

    private fun importVm(store: ViewModelStore = ViewModelStore()) =
        store.put(ImportViewModel::class.java) { ImportViewModel(context, session, repo(), codec, picker) }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            assertTrue("기다리다 끝났다: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    /** 선택기를 띄우고 [stayMs] 머문 뒤 [uri] 를 골라 돌아온다. 잠기면 금고 분기의 ViewModel 이 비워진다. */
    private fun pickAndReturn(purpose: Purpose, uri: Uri, stayMs: Long, store: ViewModelStore) = instrumentation.runOnMainSync {
        picker.opening()
        autoLock.onBackground()
        assertEquals("선택기 동안 백그라운드 즉시 잠금은 보류된다", SessionState.Unlocked, session.state.value)
        clocks.elapsed += stayMs
        autoLock.onForeground()
        if (session.state.value != SessionState.Unlocked) store.clear()
        picker.onResult(purpose, uri) // 결과는 ON_START 뒤에 온다
    }

    private fun unlock() = assertEquals(UnlockOutcome.Success, runBlocking { session.unlock(MASTER.toCharArray()) })

    @Test
    fun exportContinuesAfterTheVaultLockedOnReturnFromThePicker() {
        val file = File(dir, "out.pvault").apply { createNewFile() } // 선택기가 만들어 두는 빈 문서
        val store = ViewModelStore()
        exportVm(store)

        pickAndReturn(Purpose.EXPORT, Uri.fromFile(file), stayMs = 20_000, store)
        assertEquals("오래 머물렀다 — 돌아오는 순간 잠긴다", SessionState.Locked, session.state.value)
        assertEquals("고른 위치는 잠금을 넘겨 남는다", BackupFilePicker.Picked(Purpose.EXPORT, Uri.fromFile(file)), picker.picked.value)

        unlock()
        val host = onMain { VaultHostViewModel(session, BiometricEnroller(session, BiometricKeyStore(context)), picker) }
        assertEquals("해제하면 내보내기 화면으로 간다", "backup/export", host.consumeResume())
        assertNull("한 번만", host.consumeResume())

        val vm = exportVm()
        waitUntil("재인증부터 이어간다") { vm.step == ExportStep.REAUTH }
        assertNull(picker.picked.value)
        instrumentation.runOnMainSync { vm.reauthenticate(MASTER.toCharArray()) }
        waitUntil("백업 비밀번호") { vm.step == ExportStep.PASSWORD }
        instrumentation.runOnMainSync { vm.setBackupPassword(BACKUP.toCharArray()) }
        waitUntil("기록") { vm.step == ExportStep.DONE }

        val restored = codec.decode(file.readBytes(), BACKUP.toCharArray()) as BackupResult.Success
        assertEquals(listOf("GitHub"), restored.entries.map { it.title })
    }

    @Test
    fun exportGoesStraightOnWhenTheReturnWasInTime() {
        val file = File(dir, "out.pvault").apply { createNewFile() }
        val store = ViewModelStore()
        val vm = exportVm(store)

        pickAndReturn(Purpose.EXPORT, Uri.fromFile(file), stayMs = 14_000, store)
        assertEquals(SessionState.Unlocked, session.state.value)
        waitUntil("같은 화면이 받는다") { vm.step == ExportStep.REAUTH }

        instrumentation.runOnMainSync {
            clocks.elapsed += 14_000
            autoLock.tick()
        }
        assertEquals("선택기에서 고른 것이 상호작용이다", SessionState.Unlocked, session.state.value)
    }

    @Test
    fun importContinuesAfterTheVaultLockedOnReturnFromThePicker() {
        val backup = runBlocking { repo().exportAll() }
        val file = File(dir, "in.pvault").apply { writeBytes(codec.encode(backup, BACKUP.toCharArray(), "1.0.0", 1)) }
        runBlocking { repo().save(EntryDraft(null, "백업 뒤에 만든 항목", false, EntryContent.Note("x"))) }
        val store = ViewModelStore()
        importVm(store)

        pickAndReturn(Purpose.IMPORT, Uri.fromFile(file), stayMs = 20_000, store)
        assertEquals(SessionState.Locked, session.state.value)

        unlock()
        val host = onMain { VaultHostViewModel(session, BiometricEnroller(session, BiometricKeyStore(context)), picker) }
        assertEquals("backup/import", host.consumeResume())

        val vm = importVm()
        waitUntil("고른 파일을 읽고 재인증부터 이어간다") { vm.step == ImportStep.REAUTH }
        instrumentation.runOnMainSync { vm.reauthenticate(MASTER.toCharArray()) }
        waitUntil("백업 비밀번호") { vm.step == ImportStep.PASSWORD }
        instrumentation.runOnMainSync { vm.decrypt(BACKUP.toCharArray()) }
        waitUntil("요약 확인") { vm.step == ImportStep.CONFIRM }
        instrumentation.runOnMainSync { vm.replace() }
        waitUntil("교체") { vm.step == ImportStep.DONE }

        assertEquals(backup, runBlocking { repo().exportAll() })
    }

    @Test
    fun cancellingThePickerLeavesNothingBehind() {
        val store = ViewModelStore()
        val vm = exportVm(store)
        instrumentation.runOnMainSync {
            picker.opening()
            autoLock.onBackground()
            autoLock.onForeground()
            picker.onResult(Purpose.EXPORT, null)
        }
        assertNull(picker.picked.value)
        assertEquals(ExportStep.PICK_FILE, vm.step)
        instrumentation.runOnMainSync { autoLock.onBackground() }
        assertEquals("선택기가 닫혔으니 백그라운드 즉시 잠금이 돌아온다", SessionState.Locked, session.state.value)
    }

    private companion object {
        const val MASTER = "correct horse battery"
        const val BACKUP = "backup-pw-1234"
    }
}
