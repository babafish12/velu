package de.voci.navigation

/** Activity saved-state boundary only. Never a preference or durable-data migration. */
object NavigationSchema {
    const val KEY = "de.voci.navigation.schema"
    const val CURRENT = 2
    fun accepts(format: Int?): Boolean = format == CURRENT
}
