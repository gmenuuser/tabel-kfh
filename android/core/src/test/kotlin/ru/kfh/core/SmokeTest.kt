package ru.kfh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun coreLoads() {
        assertEquals("ru.kfh.core", CORE_PACKAGE)
    }
}
