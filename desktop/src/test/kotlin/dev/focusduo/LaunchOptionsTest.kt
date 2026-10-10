package dev.focusduo

import kotlin.test.*

class LaunchOptionsTest {
    @Test fun `starting without arguments does not enable demo`() {
        assertFalse(LaunchOptions.parse(emptyArray()).demo)
    }

    @Test fun `demo requires an explicit flag`() {
        assertTrue(LaunchOptions.parse(arrayOf("--demo")).demo)
        assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(arrayOf("--dmeo")) }
    }

    @Test fun `supported window size can be selected for visual QA`() {
        val options = LaunchOptions.parse(arrayOf("--demo", "--width=1000", "--height=700"))
        assertEquals(1000, options.width)
        assertEquals(700, options.height)
    }

    @Test fun `invalid or impractical dimensions fail before opening a window`() {
        listOf("--width=oops", "--width=0", "--height=599", "--width=4097").forEach {
            assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(arrayOf(it)) }
        }
    }

    @Test fun `help is separate from demo`() {
        val options = LaunchOptions.parse(arrayOf("--help"))
        assertTrue(options.help)
        assertFalse(options.demo)
    }
}
