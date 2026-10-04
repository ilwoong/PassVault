package io.github.ilwoong.passvault.ui

import io.github.ilwoong.passvault.ui.onboarding.PasswordStrength.FAIR
import io.github.ilwoong.passvault.ui.onboarding.PasswordStrength.STRONG
import io.github.ilwoong.passvault.ui.onboarding.PasswordStrength.VERY_STRONG
import io.github.ilwoong.passvault.ui.onboarding.PasswordStrength.WEAK
import io.github.ilwoong.passvault.ui.onboarding.passwordStrength
import io.github.ilwoong.passvault.ui.unlock.formatRemaining
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UiLogicTest {

    @Test
    fun strengthByLength() {
        assertEquals(FAIR, passwordStrength("a".repeat(11) + "b"))
        assertEquals(FAIR, passwordStrength("abcdefghijk1234"))     // 15
        assertEquals(STRONG, passwordStrength("abcdefghijk12345"))  // 16
        assertEquals(VERY_STRONG, passwordStrength("abcdefghijk123456789")) // 20
    }

    @Test
    fun commonPasswordsAreWeakIgnoringCase() {
        assertEquals(WEAK, passwordStrength("password1234"))
        assertEquals(WEAK, passwordStrength("PassWord1234"))
        assertEquals(WEAK, passwordStrength("123456789012"))
    }

    @Test
    fun singleCharRepeatIsWeakAtAnyLength() {
        assertEquals(WEAK, passwordStrength("a".repeat(30)))
    }

    @Test
    fun copyToCharArrayKeepsEveryChar() {
        assertArrayEquals("한🔐a".toCharArray(), StringBuilder("한🔐a").copyToCharArray())
    }

    @Test
    fun remainingTimeRoundsUpToWholeSeconds() {
        assertEquals("0:30", formatRemaining(30_000))
        assertEquals("0:30", formatRemaining(29_001))
        assertEquals("0:01", formatRemaining(1))
        assertEquals("15:00", formatRemaining(900_000))
    }
}
