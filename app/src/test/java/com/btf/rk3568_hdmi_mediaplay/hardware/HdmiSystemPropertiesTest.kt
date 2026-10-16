package com.btf.rk3568_hdmi_mediaplay.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdmiSystemPropertiesTest {

    @Test
    fun `parseBoolean accepts true and 1`() {
        assertTrue(HdmiSystemProperties.parseBoolean("true"))
        assertTrue(HdmiSystemProperties.parseBoolean("TRUE"))
        assertTrue(HdmiSystemProperties.parseBoolean(" 1 "))
        assertFalse(HdmiSystemProperties.parseBoolean("false"))
        assertFalse(HdmiSystemProperties.parseBoolean("0"))
        assertFalse(HdmiSystemProperties.parseBoolean(""))
        assertFalse(HdmiSystemProperties.parseBoolean(null))
    }

    @Test
    fun `efull key matches the persist rotation property`() {
        assertEquals("persist.sys.rotation.efull-1", HdmiSystemProperties.EFULL_KEY)
    }
}
