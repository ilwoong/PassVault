package io.github.ilwoong.passvault.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** LOCK-01 ~ LOCK-05, TST-08. 실제 Argon2id·AES-GCM·파일 저장소 위에서 돌린다. */
@RunWith(AndroidJUnit4::class)
class SessionManagerTest {

    private class FakeResource : SessionResource {
        var opened = 0
        var closed = 0
        var failOpen = false
        var lastKey: ByteArray? = null

        override fun onUnlocked(vaultKey: ByteArray) {
            if (failOpen) error("DB 를 열 수 없음")
            opened++
            lastKey = vaultKey.copyOf()
        }

        override fun onLocked() {
            closed++
        }
    }

    private lateinit var dir: File
    private lateinit var metaFile: File
    private val clocks = FakeClocks()
    private val pw = "correct horse battery"

    @Before
    fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sm-${System.nanoTime()}")
            .apply { mkdirs() }
        metaFile = File(dir, "vault_meta")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 새 인스턴스 = 새 프로세스 (상태를 디스크에 저장하지 않는다, LOCK-01). */
    private fun newSession(resource: SessionResource = FakeResource()) = SessionManager(
        VaultKeyManager(VaultMetaStore(metaFile), Argon2KeyDeriver(), AesGcmKeyWrapper(), clocks),
        resource,
    )

    private fun SessionManager.unlockBlocking(p: String) = runBlocking { unlock(p.toCharArray()) }

    // --- 초기 상태 ---

    @Test
    fun initialStateFollowsVaultMetaFile() {
        assertEquals(SessionState.NoVault, newSession().state.value)

        metaFile.writeBytes(ByteArray(10))
        assertEquals(SessionState.Corrupt, newSession().state.value)
    }

    // --- 생성·재시작·해제 ---

    @Test
    fun createUnlocksAndRestartComesBackLockedThenUnlocksWithSameKey() {
        val first = FakeResource()
        val a = newSession(first)
        runBlocking { a.createVault(pw.toCharArray()) }
        assertEquals(SessionState.Unlocked, a.state.value)
        assertEquals(1, first.opened)

        val second = FakeResource()
        val b = newSession(second)
        assertEquals("프로세스 시작 시 항상 잠겨 있다", SessionState.Locked, b.state.value)

        assertEquals(UnlockOutcome.Success, b.unlockBlocking(pw))
        assertEquals(SessionState.Unlocked, b.state.value)
        assertArrayEquals("같은 VK 로 DB 를 연다", first.lastKey, second.lastKey)
    }

    @Test
    fun createIsRejectedWhenVaultExists() {
        val a = newSession()
        runBlocking { a.createVault(pw.toCharArray()) }
        assertThrows(IllegalStateException::class.java) { runBlocking { newSession().createVault("x".toCharArray()) } }
    }

    // --- LOCK-04 ---

    @Test
    fun lockClosesResourceAndZeroizesVaultKey() {
        val resource = FakeResource()
        val s = newSession(resource)
        runBlocking { s.createVault(pw.toCharArray()) }
        val heldKey = s.withVaultKey { it }!! // 테스트에서만 참조를 꺼내 관찰한다

        s.lock()

        assertEquals(SessionState.Locked, s.state.value)
        assertEquals(1, resource.closed)
        assertTrue("VK 가 0 으로 지워져야 한다", heldKey.all { it == 0.toByte() })
        assertNull("잠긴 뒤에는 실패 결과 (LOCK-02)", s.withVaultKey { it })
    }

    @Test
    fun lockWhenAlreadyLockedDoesNothing() {
        val resource = FakeResource()
        val s = newSession(resource)
        runBlocking { s.createVault(pw.toCharArray()) }
        s.lock()
        s.lock()
        assertEquals(1, resource.closed)
    }

    // --- LOCK-05 / TST-08 ---

    @Test
    fun wrongPasswordStaysLockedAndIsAResult() {
        val s = createThenRestart()
        assertEquals(UnlockOutcome.WrongPassword, s.unlockBlocking("wrong"))
        assertEquals(SessionState.Locked, s.state.value)
        assertEquals(0, s.lockoutRemainingMs())
    }

    @Test
    fun fifthFailureLocksOutAndLockoutSurvivesRestart() {
        val s = createThenRestart()
        repeat(5) { assertEquals(UnlockOutcome.WrongPassword, s.unlockBlocking("wrong")) }
        assertEquals(30_000, s.lockoutRemainingMs())
        assertTrue(s.unlockBlocking("wrong") is UnlockOutcome.LockedOut)

        val restarted = newSession()
        assertEquals("재시작으로 우회할 수 없다", 30_000, restarted.lockoutRemainingMs())
        assertTrue(restarted.unlockBlocking(pw) is UnlockOutcome.LockedOut)
    }

    @Test
    fun lockoutBlocksEvenTheCorrectPasswordAndDoesNotCountAttempts() {
        val s = createThenRestart()
        repeat(5) { s.unlockBlocking("wrong") }
        assertTrue(s.unlockBlocking(pw) is UnlockOutcome.LockedOut)
        assertTrue(s.unlockBlocking("wrong") is UnlockOutcome.LockedOut)

        clocks.wall += 30_000
        clocks.elapsed += 30_000
        assertEquals("대기 중 시도는 횟수에 들어가지 않는다 (6 회째가 아님)", UnlockOutcome.Success, s.unlockBlocking(pw))
    }

    @Test
    fun movingWallClockForwardDoesNotBypassLockout() {
        val s = createThenRestart()
        repeat(5) { s.unlockBlocking("wrong") }
        clocks.wall += 24 * 60 * 60 * 1000L
        assertTrue(s.unlockBlocking(pw) is UnlockOutcome.LockedOut)
        assertTrue("재시작해도 마찬가지", newSession().unlockBlocking(pw) is UnlockOutcome.LockedOut)
    }

    @Test
    fun successResetsFailureCount() {
        val s = createThenRestart()
        repeat(4) { s.unlockBlocking("wrong") }
        assertEquals(UnlockOutcome.Success, s.unlockBlocking(pw))
        s.lock()
        repeat(4) { assertEquals(UnlockOutcome.WrongPassword, s.unlockBlocking("wrong")) }
        assertEquals("초기화되지 않았다면 여기서 대기가 걸린다", 0, s.lockoutRemainingMs())
    }

    // --- ARC-06 ---

    @Test
    fun databaseOpenFailureReportsCannotOpenAndKeepsEverything() {
        createThenRestart()
        val metaBefore = metaFile.readBytes()
        val failing = FakeResource().apply { failOpen = true }
        val s = newSession(failing)

        assertEquals(UnlockOutcome.CannotOpen, s.unlockBlocking(pw))
        assertEquals(SessionState.Locked, s.state.value)
        assertNull(s.withVaultKey { it })
        assertArrayEquals("아무것도 지우거나 다시 만들지 않는다", metaBefore, metaFile.readBytes())

        failing.failOpen = false
        assertEquals(UnlockOutcome.Success, s.unlockBlocking(pw))
    }

    private fun createThenRestart(): SessionManager {
        runBlocking { newSession().createVault(pw.toCharArray()) }
        return newSession()
    }
}
