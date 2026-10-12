package x.vladgba.sms

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun senderAndBodyMustBothMatch() {
        val matcher = Rules.Matcher(Rule(0, true, true, Mode.EQUALS, "BANK", Mode.CONTAINS, "bonus", false))
        assertTrue(matcher.matches("bank", "Your BONUS is here"))
        assertFalse(matcher.matches("SHOP", "bonus"))
        assertFalse(matcher.matches("BANK", "Account update"))
    }

    @Test fun phoneNumbersMatchAcrossNationalAndInternationalFormats() {
        val matcher = Rules.Matcher(Rule.sender(true, "+359881234567"))
        assertTrue(matcher.matches("088 123-45-67", ""))
        assertFalse(matcher.matches("0881234568", ""))
    }

    @Test fun regexQuantifiersAreNotSplitAtCommas() {
        assertEquals(listOf("BANK[0-9]{2,3}"), Rules.entries("BANK[0-9]{2,3}", true, Mode.REGEX))
        assertTrue(Rules.Cond(true, Mode.REGEX, "BANK[0-9]{2,3}", false).which("BANK123") != null)
    }

    @Test fun invalidAndEmptyRulesDoNotMatchEveryone() {
        assertFalse(Rules.Matcher(Rule.sender(true, "")).matches("BANK", "anything"))
        val matcher = Rules.Matcher(Rule(0, true, true, Mode.REGEX, "[", Mode.CONTAINS, "", false))
        assertTrue(matcher.invalid)
        assertFalse(matcher.matches("BANK", ""))
    }

    @Test fun wildcardsAndCaseSensitivityAreRespected() {
        val cond = Rules.Cond(false, Mode.WILDCARD, "Code ?*", true)
        assertNotNull(cond.which("Code 1234"))
        assertNull(cond.which("code 1234"))
    }

    @Test fun otpIgnoresAmountsAndDates() {
        assertEquals("482193", Picks.otp("Your code is 482193"))
        assertEquals("123456", Picks.otp("OTP: 123-456"))
        assertNull(Picks.otp("Login on 10.10.2026 at 12:30 costs 2500 EUR"))
    }
}
