package de.voci

import de.voci.navigation.NavigationSchema
import org.junit.Assert.*
import org.junit.Test

class NavigationSchemaTest {
    @Test fun missingAndOldFormatsAreRejected() {
        assertFalse(NavigationSchema.accepts(null))
        assertFalse(NavigationSchema.accepts(0))
        assertFalse(NavigationSchema.accepts(1))
    }
    @Test fun currentFormatIsAcceptedRepeatedly() {
        repeat(3) { assertTrue(NavigationSchema.accepts(2)) }
        assertEquals("de.voci.navigation.schema", NavigationSchema.KEY)
    }
    @Test fun unknownFormatsFailClosed() {
        assertFalse(NavigationSchema.accepts(-1))
        assertFalse(NavigationSchema.accepts(3))
        assertFalse(NavigationSchema.accepts(Int.MAX_VALUE))
    }
}
