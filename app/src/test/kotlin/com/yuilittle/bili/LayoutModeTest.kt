package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutModeTest {

    @Test
    fun modeFromKey_defaultsToPhone() {
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey(null))
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey(""))
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey("unknown"))
        assertEquals(LayoutMode.Mode.PHONE, LayoutMode.Mode.fromKey("phone"))
        assertEquals(LayoutMode.Mode.TABLET, LayoutMode.Mode.fromKey("tablet"))
    }
}
