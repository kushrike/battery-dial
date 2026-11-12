package com.kushrike.batterydial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewer("1.0.10", "1.0.9"))
        assertTrue(isNewer("1.1", "1.0.99"))
        assertTrue(isNewer("1.0.1", "1.0"))
        assertFalse(isNewer("1.0.9", "1.0.10"))
        assertFalse(isNewer("1.0.5", "1.0.5"))
        assertFalse(isNewer("1.0", "1.0.0"))
    }
}
