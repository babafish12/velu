package de.voci

import android.content.res.AssetManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.features.settings.SettingsScreen
import de.voci.ui.theme.VociTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Tests first: the current product lacks both the bundle and the Settings entry. Expected REDs
 * are explicit product assertions, after existing APK/Activity/asset/Settings fixture controls.
 * The public bundle is pinned by its reviewed index; no host files or network are used at runtime.
 * The UI case covers the entry callback only, not a future viewer or full navigation journey.
 */
class R7LicenseTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bundledLicenseIndexAndEveryTextAreComplete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("FIXTURE: instrumentation must target VOCI", "de.voci", context.packageName)
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("FIXTURE: installed package", "de.voci", installed.packageName)
        assertTrue("FIXTURE: installed versionCode must be valid", installed.longVersionCode > 0)
        assertFalse("FIXTURE: installed versionName must be present", installed.versionName.isNullOrBlank())
        val assets = context.assets
        fixture("existing target APK asset controls") {
            assertTrue("complete.json must be listed", assets.list("")!!.contains("complete.json"))
            val existing = assets.open("complete.json").use { it.readBytes() }
            assertTrue("existing animation must contain real bytes", existing.isNotEmpty())
            val animation = JSONObject(existing.toString(Charsets.UTF_8))
            assertEquals("existing animation identity", "VOCI completion", animation.getString("nm"))
            assertTrue("existing animation must contain layers", animation.getJSONArray("layers").length() > 0)
        }

        assertTrue(ASSET_MISSING, assets.list("licenses")?.contains("index.json") == true)
        val indexBytes = try { assets.open("licenses/index.json").use { it.readBytes() } }
        catch (error: FileNotFoundException) { throw AssertionError(ASSET_MISSING, error) }
        assertEquals("R7 LICENSE ASSET PRODUCT: exact reviewed public index bytes", INDEX_SHA256, sha256(indexBytes))
        val index = JSONObject(indexBytes.toString(Charsets.UTF_8))
        assertEquals("R7 LICENSE ASSET PRODUCT: schema", 1, index.getInt("schemaVersion"))
        assertEquals("R7 LICENSE ASSET PRODUCT: public index title",
            "Velu – Lizenzen und Urheberrechtshinweise", index.getString("title"))
        val entries = index.getJSONArray("entries")
        assertEquals("R7 LICENSE ASSET PRODUCT: all reviewed texts", 164, entries.length())
        val ids = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        val textsByTitle = mutableMapOf<String, String>()
        val kindsByTitle = mutableMapOf<String, String>()
        for (position in 0 until entries.length()) {
            val entry = entries.getJSONObject(position)
            val title = entry.getString("title")
            val id = entry.getString("id")
            val path = entry.getString("path")
            val hash = entry.getString("sha256")
            val size = entry.getLong("sizeBytes")
            val kind = entry.getString("kind")
            val label = "R7 LICENSE ASSET PRODUCT: $title"
            assertTrue("$label: nonempty title", title.isNotBlank())
            assertTrue("$label: unique ID", ids.add(id))
            assertTrue("$label: lowercase SHA-256 ID", Regex("[a-f0-9]{64}").matches(id))
            assertEquals("$label: content-addressed ID", id, hash)
            assertTrue("$label: exact safe text path", Regex("texts/[a-f0-9]{64}\\.txt").matches(path))
            assertEquals("$label: path must address its hash", "texts/$hash.txt", path)
            assertTrue("$label: unique text path", paths.add(path))
            assertTrue("$label: allowed kind", kind in setOf("license", "attribution", "project-license"))
            assertTrue("$label: nonempty raw text", size > 0)
            val components = entry.getJSONArray("components")
            assertTrue("$label: component attribution", components.length() > 0)
            for (component in 0 until components.length()) {
                assertTrue("$label: nonempty component", components.getString(component).isNotBlank())
            }
            val bytes = readAsset(assets, "licenses/$path")
            // Hash/length cover EVERY original byte, including whitespace, line endings and all
            // license clauses. Decode only after verification; never trim/normalize before hashing.
            assertEquals("$label: raw byte size", size, bytes.size.toLong())
            assertEquals("$label: complete original text SHA-256", hash, sha256(bytes))
            assertNull("$label: unique reviewed title", textsByTitle.put(title, bytes.toString(Charsets.UTF_8)))
            kindsByTitle[title] = kind
        }
        assertEquals("R7 LICENSE ASSET PRODUCT: unique text count", 164, ids.size)
        assertEquals("R7 LICENSE ASSET PRODUCT: shipped text directory must match the index",
            paths.map { it.removePrefix("texts/") }.toSet(), assets.list("licenses/texts")!!.toSet())
        assertEquals("R7 LICENSE ASSET PRODUCT: public bundle files only",
            setOf("index.json", "THIRD_PARTY_NOTICES.md", "texts"), assets.list("licenses")!!.toSet())
        val notices = readAsset(assets, "licenses/THIRD_PARTY_NOTICES.md")
        assertEquals("R7 LICENSE ASSET PRODUCT: complete reviewed notices byte size", 91171L, notices.size.toLong())
        assertEquals("R7 LICENSE ASSET PRODUCT: complete reviewed notices SHA-256",
            "6f82f4245b970c31d80e90fc288742482737543a40cb7af29576f0c7131bcb5a", sha256(notices))
        assertNoPrivateAssets(assets)

        // These exact titles and copyright lines come from the reviewed public index/texts.
        // Complete content is enforced above by the pinned index plus each raw text's hash/size.
        val ownTitle = "Velu – MIT License"
        assertEquals("R7 LICENSE ASSET PRODUCT: own MIT kind", "project-license", kindsByTitle[ownTitle])
        assertEquals("R7 LICENSE ASSET PRODUCT: own project license count", 1,
            kindsByTitle.values.count { it == "project-license" })
        assertTextContains(textsByTitle, ownTitle, "MIT License", "Copyright (c) 2026 Velu contributors",
            "Permission is hereby granted, free of charge", "The above copyright notice and this permission notice",
            "THE SOFTWARE IS PROVIDED \"AS IS\"", "SOFTWARE.\n")
        val protobufTitle = "Protobuf 4.28.2 / v28.2 – BSD-3-Clause"
        assertEquals("R7 LICENSE ASSET PRODUCT: Protobuf license kind", "license", kindsByTitle[protobufTitle])
        assertTextContains(textsByTitle, protobufTitle, "Copyright 2008 Google Inc.  All rights reserved.",
            "Redistributions of source code", "Redistributions in binary form", "Neither the name of Google Inc.",
            "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS",
            "support library is itself covered by the above license.")
        val threeTenTitle = "Kotlin / ThreeTenBP – BSD-3-Clause"
        assertEquals("R7 LICENSE ASSET PRODUCT: ThreeTenBP license kind", "license", kindsByTitle[threeTenTitle])
        assertTextContains(textsByTitle, threeTenTitle,
            "Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos.",
            "Redistributions of source code", "Redistributions in binary form", "Neither the name of JSR-310",
            "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS",
            "SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.")
    }

    @Test fun settingsExposeOfflineLicenseEntry() {
        var opened: String? = null
        fixture("genuine standalone Compose Activity") {
            assertEquals(ComponentActivity::class.java, compose.activity.javaClass)
            assertEquals("de.voci", compose.activity.packageName)
            assertFalse(compose.activity.isFinishing)
        }
        compose.setContent { VociTheme { SettingsScreen { opened = it } } }
        // Existing real controls must render and dispatch correctly before the intended RED.
        for ((title, route) in listOf("Konto" to "account", "Audio" to "audio",
            "Speicher" to "storage", "Datenschutz" to "privacy")) {
            fixture("existing Settings control $title") {
                compose.onNodeWithText(title).performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
                compose.runOnIdle { assertEquals(route, opened) }
            }
        }
        compose.runOnIdle { opened = null }
        assertEquals(UI_MISSING, 1, compose.onAllNodesWithText("Lizenzen").fetchSemanticsNodes().size)
        compose.onNodeWithText("Lizenzen").performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals("R7 LICENSE UI PRODUCT: license entry must dispatch the licenses route", "licenses", opened)
        }
    }

    private fun readAsset(assets: AssetManager, path: String): ByteArray = try {
        assets.open(path).use { it.readBytes() }
    } catch (error: java.io.IOException) {
        throw AssertionError("R7 LICENSE ASSET PRODUCT: required offline asset $path must be readable", error)
    }

    private fun assertNoPrivateAssets(assets: AssetManager, directory: String = "") {
        for (name in assets.list(directory).orEmpty()) {
            assertFalse("R7 LICENSE ASSET PRODUCT: private report must not ship in assets/$directory/$name",
                name == "_private" || name == "PRIVATE_PACKAGE_VERIFICATION.json")
            assertNoPrivateAssets(assets, if (directory.isEmpty()) name else "$directory/$name")
        }
    }

    private fun assertTextContains(texts: Map<String, String>, title: String, vararg fragments: String) {
        val text = texts[title] ?: throw AssertionError("R7 LICENSE ASSET PRODUCT: missing $title")
        for (fragment in fragments) {
            assertTrue("R7 LICENSE ASSET PRODUCT: $title must retain $fragment", text.contains(fragment))
        }
    }

    private fun fixture(label: String, block: () -> Unit) {
        try { block() }
        catch (error: AssertionError) { throw AssertionError("FIXTURE: $label", error) }
        catch (error: Exception) { throw AssertionError("FIXTURE: $label", error) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val INDEX_SHA256 = "d07a596f651a22955bc0e9c3f10a240d4b8530254421f8c84259dc5d8c7180c8"
        const val ASSET_MISSING = "R7 LICENSE ASSET PRODUCT: offline license bundle must be shipped"
        const val UI_MISSING = "R7 LICENSE UI PRODUCT: settings must expose offline licenses"
    }
}
