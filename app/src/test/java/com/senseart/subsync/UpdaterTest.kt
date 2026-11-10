package com.senseart.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionCompare() {
        assertTrue(Updater.isNewer("1.5", "1.4"))
        assertTrue(Updater.isNewer("v1.10", "1.9"))
        assertTrue(Updater.isNewer("2.0", "1.99"))
        assertFalse(Updater.isNewer("1.4", "1.4"))
        assertFalse(Updater.isNewer("1.3", "1.4"))
        assertTrue(Updater.isNewer("1.4.1", "1.4"))
    }
}
