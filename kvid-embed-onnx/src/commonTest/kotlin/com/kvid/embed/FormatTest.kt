package com.kvid.embed

import kotlin.test.Test
import kotlin.test.assertEquals

/** [fmt] gives what `String.format(Locale.ROOT, …)` gave the log lines before they moved to commonTest. */
class FormatTest {
    @Test fun fixedPoint() {
        assertEquals("1.00000000", fmt("%.8f", 1.0))
        assertEquals("0.99999123", fmt("%.8f", 0.999991234))
        assertEquals("1567.8", fmt("%.1f", 1567.84))
        assertEquals("0.1", fmt("%.1f", 0.05))
        assertEquals("0.679", fmt("%.3f", 0.6789))
        assertEquals("-0.500", fmt("%.3f", -0.5))
        assertEquals("383980.1", fmt("%.1f", 383980.1))
        assertEquals("0.0", fmt("%.1f", 0.0))
    }

    @Test fun scientific() {
        assertEquals("1.490e-07", fmt("%.3e", 1.4901161193847656e-07))
        assertEquals("0.000e+00", fmt("%.3e", 0.0))
        assertEquals("1.000e+01", fmt("%.3e", 9.9996))
        assertEquals("2.384e-07", fmt("%.3e", 2.384185791015625e-07))
        assertEquals("1.234e+05", fmt("%.3e", 123400.0))
        assertEquals("-3.000e-03", fmt("%.3e", -0.003))
    }
}
