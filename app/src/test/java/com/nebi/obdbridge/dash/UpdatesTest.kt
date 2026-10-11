package com.nebi.obdbridge.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun versionCompare() {
        assertTrue(Updates.newer("v1.1", "1.0"))
        assertTrue(Updates.newer("1.10", "1.9"))
        assertTrue(Updates.newer("2", "1.9.9"))
        assertFalse(Updates.newer("v1.0", "1.0"))
        assertFalse(Updates.newer("1.0.0", "1.0"))
        assertFalse(Updates.newer("1.0-beta", "1.0"))
        assertFalse(Updates.newer("0.9", "1.0"))
    }
}
