package io.github.ilwoong.passvault.ui

import io.github.ilwoong.passvault.ui.common.ClipboardAccess
import io.github.ilwoong.passvault.ui.common.CurrentClip
import io.github.ilwoong.passvault.ui.common.SecureClipboard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LOCK-07 판정 로직. 시스템 클립보드를 흉내 내고 시간은 가상으로 흐른다. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureClipboardTest {

    private class FakeClipboard : ClipboardAccess {
        var text: String? = null
        var nonce: String? = null
        var readable = true
        override fun set(value: String, nonce: String) {
            text = value
            this.nonce = nonce
        }
        override fun current() = if (readable) CurrentClip.Readable(nonce) else CurrentClip.Unreadable
        override fun clear() {
            text = null
            nonce = null
        }
        /** 다른 앱이 복사했다 — 우리 표식이 없다. */
        fun otherAppCopies(value: String) {
            text = value
            nonce = null
        }
    }

    private val fake = FakeClipboard()
    private val scope = TestScope()
    private var seconds = 30
    private val clipboard = SecureClipboard(fake, { seconds }, scope)

    @Test
    fun clearsAfterConfiguredTime() {
        assertEquals(30, clipboard.copy("S3cret"))
        scope.advanceTimeBy(29_999); scope.runCurrent()
        assertEquals("S3cret", fake.text)
        scope.advanceTimeBy(1); scope.runCurrent()
        assertNull(fake.text)
    }

    @Test
    fun zeroNeverClears() {
        seconds = 0
        assertEquals(0, clipboard.copy("S3cret"))
        scope.advanceTimeBy(10 * 60_000); scope.runCurrent()
        assertEquals("S3cret", fake.text)
    }

    @Test
    fun laterCopyByAnotherAppIsLeftAlone() {
        clipboard.copy("S3cret")
        fake.otherAppCopies("someone else")
        scope.advanceTimeBy(30_000); scope.runCurrent()
        assertEquals("남의 클립보드를 지우지 않는다", "someone else", fake.text)
    }

    @Test
    fun unreadableInBackgroundIsClearedAnyway() {
        clipboard.copy("S3cret")
        fake.otherAppCopies("someone else")
        fake.readable = false // Android 10+ 백그라운드
        scope.advanceTimeBy(30_000); scope.runCurrent()
        assertNull("확인할 수 없으면 지운다 — 수용한 대가 (LOCK-07)", fake.text)
    }

    @Test
    fun recopyRestartsTimerAndOnlyTheLatestCounts() {
        clipboard.copy("first")
        scope.advanceTimeBy(20_000); scope.runCurrent()
        clipboard.copy("second")
        scope.advanceTimeBy(20_000); scope.runCurrent()
        assertEquals("second", fake.text)
        scope.advanceTimeBy(10_000); scope.runCurrent()
        assertNull(fake.text)
    }

    @Test
    fun manualClearWithoutCopyDoesNothing() {
        fake.otherAppCopies("user text")
        clipboard.clearIfOurs()
        assertEquals("user text", fake.text)
    }

    @Test
    fun manualClearCancelsTheTimer() {
        clipboard.copy("S3cret")
        clipboard.clearIfOurs()
        assertNull(fake.text)
        fake.otherAppCopies("later user text")
        scope.advanceTimeBy(60_000); scope.runCurrent()
        assertEquals("타이머가 남아 남의 것을 지우면 안 된다", "later user text", fake.text)
    }
}
