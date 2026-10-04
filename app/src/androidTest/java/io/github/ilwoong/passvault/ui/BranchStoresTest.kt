package io.github.ilwoong.passvault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilwoong.passvault.security.AesGcmKeyWrapper
import io.github.ilwoong.passvault.security.Argon2KeyDeriver
import io.github.ilwoong.passvault.security.FakeClocks
import io.github.ilwoong.passvault.security.SessionManager
import io.github.ilwoong.passvault.security.SessionResource
import io.github.ilwoong.passvault.security.VaultKeyManager
import io.github.ilwoong.passvault.security.VaultMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** LOCK-04 4 단계, TST-08: 잠기는 즉시, 컴포지션 없이 금고 분기의 ViewModel 이 비워진다. */
@RunWith(AndroidJUnit4::class)
class BranchStoresTest {

    class Probe : ViewModel() {
        var cleared = false
        public override fun onCleared() {
            cleared = true
        }
    }

    /** 닫히는 시점에 ViewModel 이 이미 비워져 있었는지 기록한다. */
    private class Resource : SessionResource {
        var probe: Probe? = null
        var clearedBeforeClose: Boolean? = null
        override fun onUnlocked(vaultKey: ByteArray) = Unit
        override fun onLocked() {
            clearedBeforeClose = probe?.cleared
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var dir: File
    private val resource = Resource()
    private lateinit var session: SessionManager

    @Before
    fun setUp() {
        dir = File(instrumentation.targetContext.cacheDir, "bs-${System.nanoTime()}").apply { mkdirs() }
        session = SessionManager(
            VaultKeyManager(VaultMetaStore(File(dir, "vault_meta")), Argon2KeyDeriver(), AesGcmKeyWrapper(), FakeClocks()),
            resource,
        )
        runBlocking { session.createVault("correct horse battery".toCharArray()) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun probeIn(stores: BranchStores, key: String) =
        ViewModelProvider(stores.storeFor(key), ViewModelProvider.NewInstanceFactory())[Probe::class.java]

    @Test
    fun lockClearsVaultViewModelsImmediatelyAndBeforeTheDatabaseCloses() {
        instrumentation.runOnMainSync {
            val stores = BranchStores(session)
            val probe = probeIn(stores, VAULT_BRANCH)
            resource.probe = probe
            assertFalse(probe.cleared)

            session.lock()

            assertTrue("컴포지션을 기다리지 않는다", probe.cleared)
            assertEquals("DB 를 쓰는 쪽을 먼저 멈춘다", true, resource.clearedBeforeClose)
        }
    }

    @Test
    fun otherBranchesAreLeftAlone() {
        instrumentation.runOnMainSync {
            val stores = BranchStores(session)
            val probe = probeIn(stores, "unlock")
            session.lock()
            assertFalse("해제 분기의 복구 흐름(BK-05)은 상태 변화로 끊기지 않는다", probe.cleared)
        }
    }
}
