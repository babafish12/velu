package de.voci

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Additional source-first coverage, not an executed RED. Uses MainActivity's real
 * VociNavigation, real assets and Activity recreation. Never clears or seeds user data,
 * changes authentication, starts a lesson, or substitutes successful repository results.
 */
class R7LicenseNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val scroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val ownTitle = "Velu – MIT License"

    @Test fun settingsOwnMitRecreationAndBackUseRealNavigation() {
        existingPrerequisites()
        val original = bundledText(ownTitle)
        product("own MIT contains complete copyright, permission, condition and disclaimer") {
            for (clause in listOf("Copyright (c) 2026 Velu contributors",
                "Permission is hereby granted, free of charge",
                "The above copyright notice and this permission notice",
                "THE SOFTWARE IS PROVIDED \"AS IS\"", "SOFTWARE.\n")) {
                assertTrue("Missing clause: $clause", original.contains(clause))
            }
        }
        openLicenses()
        product("own MIT opens from actual list and renders the complete original text") {
            compose.onNodeWithText(ownTitle).performScrollTo().performClick()
            awaitText(original)
            assertHeading(ownTitle)
            settingsTab().assertIsSelected()
            assertCompleteOwnText(original)
        }
        val oldActivity = compose.activity
        compose.activityRule.scenario.recreate()
        product("Activity recreation preserves the opened MIT destination and selected text") {
            awaitText(original)
            assertNotSame(oldActivity, compose.activity)
            assertHeading(ownTitle)
            settingsTab().assertIsSelected()
            assertCompleteOwnText(original)
        }
        systemBack()
        product("detail Back returns to license list") {
            assertHeading("Lizenzen")
            compose.onNodeWithText(ownTitle).performScrollTo().assertIsDisplayed()
            settingsTab().assertIsSelected()
        }
        systemBack()
        product("list Back returns to Settings with existing entries") {
            assertHeading("Einstellungen")
            for (title in listOf("Konto", "Audio", "Speicher", "Datenschutz", "Lizenzen")) {
                compose.onNodeWithText(title).performScrollTo().assertIsDisplayed().assertHasClickAction()
            }
        }
    }

    @Test fun changingTextShowsBsdCopyrightsAndCompleteNoticesOffline() {
        existingPrerequisites()
        openLicenses()
        for ((title, copyright) in listOf(
            "Protobuf 4.28.2 / v28.2 – BSD-3-Clause" to "Copyright 2008 Google Inc.  All rights reserved.",
            "Kotlin / ThreeTenBP – BSD-3-Clause" to "Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos."
        )) {
            val original = bundledText(title)
            product("$title opens the correct complete text, including BSD copyright") {
                assertTrue(original.contains(copyright))
                compose.onNodeWithText(title).performScrollTo().performClick()
                awaitTextFragment(copyright)
                assertHeading(title)
                compose.onNodeWithText(copyright, substring = true).performScrollTo().assertIsDisplayed()
                assertRenderedDocument(original)
                compose.onNodeWithText("Copyright (c) 2026 Velu contributors", substring = true).assertDoesNotExist()
            }
            systemBack()
            assertHeading("Lizenzen")
        }
        val notices = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("licenses/THIRD_PARTY_NOTICES.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        product("complete third-party notices are reachable in the native viewer") {
            compose.onNodeWithText("Dritthinweise (vollständig)").performScrollTo().performClick()
            awaitTextFragment(notices.take(80))
            assertHeading("Dritthinweise")
            assertRenderedDocument(notices)
            settingsTab().assertIsSelected()
        }
        systemBack()
        assertHeading("Lizenzen")
        systemBack()
        assertHeading("Einstellungen")
    }

    private fun existingPrerequisites() = labeled("FIXTURE: existing target Activity/assets/Settings") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("de.voci", context.packageName)
        assertEquals(MainActivity::class.java, compose.activity.javaClass)
        val animation = context.assets.open("complete.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("VOCI completion", animation.getString("nm"))
        assertTrue(animation.getJSONArray("layers").length() > 0)
        // An existing active full-screen session must be finished by Parent, never wiped here.
        settingsTab().assertIsDisplayed().performClick()
        assertHeading("Einstellungen")
        for (title in listOf("Konto", "Audio", "Speicher", "Datenschutz")) {
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
    }

    private fun openLicenses() = product("Settings opens the real offline license destination") {
        compose.onNodeWithText("Lizenzen").performScrollTo().assertIsDisplayed().performClick()
        awaitText(ownTitle)
        assertHeading("Lizenzen")
        settingsTab().assertIsSelected()
    }

    private fun bundledText(title: String): String {
        var text = ""
        product("required real asset for $title is present") {
            val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
            val index = assets.open("licenses/index.json").bufferedReader().use { JSONObject(it.readText()) }
            val entries = index.getJSONArray("entries")
            val entry = (0 until entries.length()).map { entries.getJSONObject(it) }
                .single { it.getString("title") == title }
            val id = entry.getString("id")
            assertTrue(Regex("[a-f0-9]{64}").matches(id))
            assertEquals("texts/$id.txt", entry.getString("path"))
            text = assets.open("licenses/texts/$id.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return text
    }

    private fun assertCompleteOwnText(original: String) {
        compose.onNodeWithText(original).assertExists()
        assertRenderedDocument(original)
        scrollToEnd()
        compose.onNodeWithText(original).assertIsDisplayed()
    }

    private fun assertRenderedDocument(original: String) {
        // Tags identify only the viewer's visible native text blocks; compare their entire
        // semantics payload in order, including all whitespace, against the actual asset.
        val blocks = compose.onAllNodes(hasTestTag("license-text"), useUnmergedTree = true).fetchSemanticsNodes()
        val rendered = blocks.joinToString("") { node ->
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        }
        assertEquals("R7 LICENSE NAV PRODUCT: no omitted or rewritten document bytes after UTF-8 decoding", original, rendered)
        compose.onNodeWithText("Gesamten Text kopieren").performScrollTo().assertHasClickAction()
        scrollToEnd()
    }

    private fun scrollToEnd() {
        val range = compose.onNode(scroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.onNode(scroll).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue()) }
        compose.waitForIdle()
        val end = compose.onNode(scroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("R7 LICENSE NAV PRODUCT: document end reachable", end.maxValue(), end.value(), 1f)
    }

    private fun settingsTab() = compose.onNode(hasText("Einstellungen") and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    private fun assertHeading(title: String) {
        compose.onNode(hasText(title) and heading).performScrollTo().assertIsDisplayed()
    }
    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1
    }
    private fun awaitTextFragment(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size == 1
    }
    private fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun product(label: String, block: () -> Unit) = labeled("R7 LICENSE NAV PRODUCT: $label", block)
    private fun labeled(label: String, block: () -> Unit) {
        try { block() }
        catch (error: AssertionError) { throw AssertionError("$label\n${error.message}", error) }
        catch (error: Exception) { throw AssertionError(label, error) }
    }
}
