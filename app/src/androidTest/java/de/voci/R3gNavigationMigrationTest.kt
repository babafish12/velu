package de.voci

import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.PlaybackService
import de.voci.audio.PlaybackState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Real MainActivity/UI, without NavController access, fabricated entries, or reflection.
 * Exactly three independent JUnit cases: an assertion failure in legacy does not skip controls.
 * Only our transient search and two newly owned cache artifacts are written by this fixture.
 * Existing product startup/read-only Library collectors still run normally. Parent must first
 * naturally complete playback; the fixture neither decodes durable records nor repairs them.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class R3gNavigationMigrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    companion object {
        private const val SEARCH = "R3G0A saved Library draft 8f2c1a"
        private const val PRODUCT = "MIGRATION PRODUCT: legacy navigation must restart at Sprint:"
        private const val ORIGINAL_APK = "a8088470e00f54bffa0255043e2b0d1494de77609e2ed5e4835026b74ec13b6c"
        private const val PAYLOAD_LIMIT = 262144
        private const val METADATA_LIMIT = 4096
        private const val PAYLOAD_PIN = "r3gLegacyPayloadSha256"
        private const val METADATA_PIN = "r3gLegacyMetadataSha256"
    }

    private lateinit var runner: R3gMigrationRunner
    private lateinit var app: VociApp
    private var scenario: ActivityScenario<MainActivity>? = null
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val search = hasText("Suchen") and hasSetTextAction()

    private data class Owner(val activity: MainActivity, val vm: VociViewModel) {
        fun json() = JSONObject().put("activity", R3gMigrationRunner.identity(activity))
            .put("view_model", R3gMigrationRunner.identity(vm))
    }

    private data class Observation(
        val sprint: Boolean, val library: Boolean, val sprintSelected: Boolean,
        val librarySelected: Boolean, val searchCount: Int, val exactSearch: Boolean,
        val emptySearch: Boolean, val modelExactSearch: Boolean, val modelEmptySearch: Boolean
    ) {
        fun json() = JSONObject().put("sprint_heading_visible", sprint).put("library_heading_visible", library)
            .put("sprint_tab_selected", sprintSelected).put("library_tab_selected", librarySelected)
            .put("search_controls", searchCount).put("exact_owned_search", exactSearch)
            .put("empty_search", emptySearch).put("model_exact_owned_search", modelExactSearch)
            .put("model_empty_search", modelEmptySearch)
        fun restored() = library && librarySelected && !sprint && searchCount == 1 && exactSearch && modelExactSearch
    }

    @Test fun legacySavedNavigationRestartsAtSprint_migrationRed() = case(
        "legacySavedNavigationRestartsAtSprint_migrationRed"
    ) {
        val old = establishLibrary()
        val captured = capture(old)
        // Must be preserved and independently reread before any migration assertion.
        val legacy = fixture("capture/pin or read original legacy artifact") { legacyInput(captured, old) }
        val fresh = freshReplay(old, legacy, "original-legacy")
        val seen = observe(fresh, "legacy-replay-observed")
        main { runner.verifyTransport(expectedInjections = 1, minimumSaves = 1) }
        assertTrue("$PRODUCT ${seen.json()}", seen.sprint && seen.sprintSelected && !seen.library &&
            !seen.librarySelected && seen.searchCount == 0 && seen.modelEmptySearch)
        // Reaching Sprint alone is insufficient: the old hidden Library draft must also be gone.
        clickTab("Bibliothek")
        val library = observe(fresh, "library-after-migration")
        assertTrue("$PRODUCT old Library draft must be discarded: ${library.json()}",
            library.library && library.librarySelected && library.searchCount == 1 &&
                library.emptySearch && library.modelEmptySearch)
    }

    @Test fun markedCurrentFormatRestoresPageAndDraft_positiveControl() = case(
        "markedCurrentFormatRestoresPageAndDraft_positiveControl"
    ) {
        val old = establishLibrary()
        val original = capture(old)
        val marked = fixture("public Parcel copy for injected-marker control") { decode(encode(original)) }
        marked.putInt(R3gMigrationRunner.SCHEMA_KEY, R3gMigrationRunner.CURRENT_SCHEMA)
        runner.packet("control-marker-injected", JSONObject().put("source_marker", R3gMigrationRunner.marker(original))
            .put("replay_marker", R3gMigrationRunner.marker(marked)).put("marker_origin", "injected-format2"))
        val fresh = freshReplay(old, marked, "injected-format2")
        val seen = observe(fresh, "injected-marker-replay-observed")
        main { runner.verifyTransport(expectedInjections = 1, minimumSaves = 1) }
        assertTrue("CONTROL: injected-marker saved-Bundle replay must restore exact Library/search: ${seen.json()}",
            seen.restored())
        // This demonstrates transport only. It never claims R3D emitted schema2.
    }

    @Test fun ordinaryActivityRecreationPreservesPageAndDraft_positiveControl() = case(
        "ordinaryActivityRecreationPreservesPageAndDraft_positiveControl"
    ) {
        val old = establishLibrary()
        fixture("ordinary ActivityScenario.recreate without injection") {
            runner.packet("ordinary-recreate-begin", old.json())
            scenario!!.recreate()
            compose.waitForIdle()
        }
        val current = owner()
        fixture("ordinary Activity replacement and retained ViewModel") {
            assertNotSame(old.activity, current.activity)
            assertSame(old.vm, current.vm) // Ordinary configuration recreation legitimately retains it.
            assertEquals(Lifecycle.State.DESTROYED, main { old.activity.lifecycle.currentState })
        }
        runner.packet("ordinary-recreate-end", JSONObject().put("old", old.json()).put("new", current.json())
            .put("injected", false).put("marker_origin", "original-framework"))
        val seen = observe(current, "ordinary-recreation-observed")
        main { runner.verifyTransport(expectedInjections = 0, minimumSaves = 1) }
        assertTrue("CONTROL: ordinary Activity recreation must restore exact Library/search: ${seen.json()}",
            seen.restored())
        // Actual onSave marker is reported, never injected or required to be 2 in this control.
    }

    private fun establishLibrary(): Owner = fixture("real fresh Sprint then Library/search UI setup") {
        launch()
        compose.waitForIdle()
        compose.onNode(hasText("Sprint") and heading).assertIsDisplayed()
        compose.onNode(tab("Sprint")).assertIsSelected()
        val owner = owner()
        assertTrue(main { owner.vm.query.value.isEmpty() })
        clickTab("Bibliothek")
        compose.onAllNodes(search).assertCountEquals(1)
        compose.onNode(search).performScrollTo().assertIsDisplayed().assertIsEnabled().performTextReplacement(SEARCH)
        compose.waitForIdle()
        assertTrue(observe(owner, "source-library-established").restored())
        owner
    }

    private fun capture(owner: Owner): Bundle = fixture("framework Activity saved-state capture") {
        guardPlayback()
        val result = main { runner.capture(owner.activity) }
        runner.packet("source-capture", owner.json().put("marker", R3gMigrationRunner.marker(result))
            .put("marker_origin", "original-product-output").put("owned_search", SEARCH))
        result
    }

    private fun freshReplay(old: Owner, bundle: Bundle, origin: String): Owner = fixture("fresh saved-Bundle replay") {
        runner.packet("fresh-replay-begin", old.json().put("marker_origin", origin))
        scenario!!.close()
        scenario = null
        assertEquals(Lifecycle.State.DESTROYED, main { old.activity.lifecycle.currentState })
        runner.packet("source-destroyed", old.json())
        main { runner.armFreshReplay(bundle, origin) }
        launch()
        compose.waitForIdle()
        val fresh = owner()
        assertNotSame("FIXTURE: fresh Activity required", old.activity, fresh.activity)
        assertNotSame("FIXTURE: fresh ViewModel required; retained state is not evidence", old.vm, fresh.vm)
        runner.packet("fresh-replay-end", JSONObject().put("old", old.json()).put("new", fresh.json())
            .put("marker_origin", origin).put("kind", "saved-Bundle replay after finishing source"))
        fresh
    }

    private fun launch() {
        val intent = main { runner.launchIntent() }
        scenario = ActivityScenario.launch<MainActivity>(intent)
    }

    /** Public ViewModelProvider lookup, with a factory that REFUSES to create a test substitute.
     * Compose's default Activity owner/key must already contain the actual product ViewModel.
     */
    private fun owner(): Owner = fixture("existing real Activity/ViewModel lookup") {
        var result: Owner? = null
        scenario!!.onActivity { activity ->
            val refusingFactory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    throw AssertionError("FIXTURE: production ViewModel was not already created")
            }
            result = Owner(activity, ViewModelProvider(activity, refusingFactory)[VociViewModel::class.java])
        }
        requireNotNull(result)
    }

    private fun tab(label: String) = hasText(label) and hasClickAction() and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun clickTab(label: String) = fixture("unique visible enabled $label tab") {
        compose.onAllNodes(tab(label)).assertCountEquals(1)
        compose.onNode(tab(label)).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun observe(owner: Owner, label: String): Observation = fixture("settled navigation observation") {
        compose.waitForIdle()
        guardPlayback()
        fun visible(matcher: SemanticsMatcher): Boolean =
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 && compose.onNode(matcher).isDisplayed()
        fun selected(name: String): Boolean {
            compose.onAllNodes(tab(name)).assertCountEquals(1)
            return compose.onNode(tab(name)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
        }
        val fields = compose.onAllNodes(search).fetchSemanticsNodes()
        val text = fields.singleOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text
        val model = main { owner.vm.query.value }
        val seen = Observation(visible(hasText("Sprint") and heading), visible(hasText("Bibliothek") and heading),
            selected("Sprint"), selected("Bibliothek"), fields.size,
            text == SEARCH && visible(search), text == "" && visible(search), model == SEARCH, model.isEmpty())
        // Never emit actual arbitrary UI/model text, Bundle keys, preferences, credentials, or lesson IDs.
        runner.packet(label, owner.json().put("navigation", seen.json()))
        seen
    }

    private fun case(name: String, action: () -> Unit) {
        runner = InstrumentationRegistry.getInstrumentation() as? R3gMigrationRunner
            ?: throw AssertionError("FIXTURE: use de.voci.test/de.voci.R3gMigrationRunner")
        var failure: Throwable? = null
        var playbackReady = false
        try {
            main { runner.beginCase(name) }
            fixture("Parent completed-baseline prerequisite and actual empty Media3 playlist") {
                // No reading/decoding of session files, auth delegates or DB records in test setup.
                // Parent attests natural completion before this process can initialize playback.
                assertEquals("true", InstrumentationRegistry.getArguments().getString("r3gParentCompletedBaseline"))
                app = runner.targetContext.applicationContext as VociApp
                main { app.playback }
                compose.waitUntil(10000) { main { PlaybackService.localPlayer != null } }
                val actualPlayback = guardPlayback()
                playbackReady = true
                runner.packet("fixture-ready", JSONObject().put("parent_completed_baseline_attested", true)
                    .put("playback", actualPlayback))
            }
            action()
        } catch (caught: Throwable) {
            val known = caught is AssertionError && listOf(PRODUCT, "CONTROL:", "FIXTURE:")
                .any { caught.message.orEmpty().startsWith(it) }
            failure = if (known) caught else AssertionError("FIXTURE: unexpected case failure (${caught.javaClass.simpleName})")
        } finally {
            // Cleanup failure supersedes any intended RED: Parent must reject the run as FIXTURE.
            try {
                fixture("close owned Activity and preserve empty playback") {
                    scenario?.close()
                    scenario = null
                    val actualPlayback = if (playbackReady) guardPlayback() else null
                    runner.packet("cleanup-preserved", JSONObject().put("cache_cleanup_attempted", false)
                        .put("playback", actualPlayback ?: JSONObject.NULL))
                }
            } catch (cleanup: Throwable) {
                failure = cleanup
            } finally {
                try {
                    val classification = when {
                        failure == null -> "assertions-passed"
                        failure!!.message.orEmpty().startsWith(PRODUCT) -> "migration-product-assertion-failed"
                        failure!!.message.orEmpty().startsWith("CONTROL:") -> "control-assertion-failed"
                        else -> "fixture-failed"
                    }
                    runner.packet("case-result", JSONObject().put("classification", classification))
                } finally {
                    main { runner.endCase() }
                    scenario = null
                }
            }
        }
        failure?.let { throw it }
    }

    private fun guardPlayback() = main {
        val player = PlaybackService.localPlayer ?: throw AssertionError("FIXTURE: real Media3 not ready")
        assertEquals("FIXTURE: Parent must leave actual playlist empty; never clear it", 0, player.mediaItemCount)
        assertFalse("FIXTURE: playback must remain stopped", player.isPlaying)
        assertFalse("FIXTURE: no pending play intent", player.playWhenReady)
        assertTrue("FIXTURE: empty product playback required", app.playback.state.value == PlaybackState())
        assertNull("FIXTURE: no preparation", app.playback.preparing.value)
        assertTrue("FIXTURE: no answer/session mutation", app.playback.answers.value.isEmpty() && app.playback.uncertain.value.isEmpty())
        JSONObject().put("playlist_items", player.mediaItemCount).put("playing", player.isPlaying)
            .put("play_when_ready", player.playWhenReady).put("has_session", app.playback.state.value.session != null)
            .put("preparing", app.playback.preparing.value != null)
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runner.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private inline fun <T> fixture(label: String, block: () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        // UI assertion diagnostics may include arbitrary existing content; keep fixture output bounded.
        val safeDetail = failure.message?.takeIf { it.startsWith("FIXTURE:") }
            ?.substringBefore('\n')?.take(512)
        throw AssertionError("FIXTURE: $label (${failure.javaClass.simpleName})" +
            if (safeDetail == null) "" else ": $safeDetail")
    }

    private fun encode(state: Bundle): ByteArray {
        assertFalse("FIXTURE: descriptors are not portable legacy input", state.hasFileDescriptors())
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            assertTrue("FIXTURE: bounded Parcel payload", parcel.dataSize() in 1..PAYLOAD_LIMIT)
            return parcel.marshall().also { assertTrue(it.size in 1..PAYLOAD_LIMIT) }
        } finally { parcel.recycle() }
    }

    private fun decode(bytes: ByteArray): Bundle {
        assertTrue(bytes.size in 1..PAYLOAD_LIMIT)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val state = requireNotNull(parcel.readBundle(MainActivity::class.java.classLoader))
            assertEquals(0, parcel.dataAvail())
            assertFalse(state.isEmpty)
            assertFalse(state.hasFileDescriptors())
            return state
        } finally { parcel.recycle() }
    }

    private fun legacyInput(captured: Bundle, owner: Owner): Bundle {
        val root = ownedDirectory()
        val payload = File(root, "activity-state.parcel")
        val metadata = File(root, "metadata.json")
        val args = InstrumentationRegistry.getArguments()
        val payloadPin = args.getString(PAYLOAD_PIN)
        val metadataPin = args.getString(METADATA_PIN)
        val existing = root.list()?.toSet() ?: error("Cannot list owned directory")
        assertTrue("FIXTURE: unexpected pre-existing artifact", existing.all { it in setOf(payload.name, metadata.name) })
        if (existing.isNotEmpty()) {
            assertEquals("FIXTURE: partial fixture must never be overwritten", setOf(payload.name, metadata.name), existing)
            assertTrue("FIXTURE: Parent must supply both independent pins", validHash(payloadPin) && validHash(metadataPin))
        } else {
            assertNull("FIXTURE: missing pinned input must be restored by Parent", payloadPin)
            assertNull("FIXTURE: missing pinned input must be restored by Parent", metadataPin)
            assertFalse("FIXTURE: only genuinely missing original marker may be captured as legacy",
                captured.containsKey(R3gMigrationRunner.SCHEMA_KEY))
            // Pin the original installed R3D APK, not a later unmarked candidate with unknown provenance.
            val apkHash = hashFile(File(app.applicationInfo.sourceDir))
            assertEquals("FIXTURE: first capture requires Parent's original R3D main APK", ORIGINAL_APK, apkHash)
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            assertEquals("de.voci", app.packageName)
            assertEquals(1L, info.longVersionCode)
            assertEquals("0.1.0", info.versionName)
            val bytes = encode(captured)
            val meta = JSONObject().put("fixture", "r3g-r3d-original-v1")
                .put("serialization", "Parcel.writeBundle/marshall")
                .put("source_marker", "missing").put("source_marker_injected", false)
                .put("package", app.packageName).put("version_code", info.longVersionCode)
                .put("version_name", info.versionName).put("component", "de.voci/de.voci.MainActivity")
                .put("main_apk_sha256", apkHash).put("payload_bytes", bytes.size)
                .put("payload_sha256", hash(bytes)).put("owned_search", SEARCH)
                .put("source_activity", R3gMigrationRunner.identity(owner.activity))
                .put("source_view_model", R3gMigrationRunner.identity(owner.vm))
                .put("source_capture", "real MainActivity callActivityOnSaveInstanceState after Library UI")
                .put("replay_kind", "fresh Activity saved-Bundle replay; not process death")
                .toString().toByteArray(Charsets.UTF_8)
            assertTrue(meta.size in 1..METADATA_LIMIT)
            // CREATE_NEW never truncates an existing file, including a racing writer or symlink.
            // An interrupted partial pair remains a FIXTURE failure; it is never deleted/relabelled.
            writeNew(payload, bytes)
            writeNew(metadata, meta)
            runner.packet("legacy-artifact-created", JSONObject().put("payload_bytes", bytes.size)
                .put("payload_sha256", hash(bytes)).put("metadata_sha256", hash(meta))
                .put("source_marker", "missing").put("marker_origin", "original-legacy"))
        }
        val bytes = readBounded(payload, PAYLOAD_LIMIT)
        val metaBytes = readBounded(metadata, METADATA_LIMIT)
        if (existing.isNotEmpty()) {
            assertEquals(payloadPin, hash(bytes))
            assertEquals(metadataPin, hash(metaBytes))
        }
        val meta = JSONObject(String(metaBytes, Charsets.UTF_8))
        assertEquals("r3g-r3d-original-v1", meta.getString("fixture"))
        assertEquals("Parcel.writeBundle/marshall", meta.getString("serialization"))
        assertEquals("missing", meta.getString("source_marker"))
        assertFalse(meta.getBoolean("source_marker_injected"))
        assertEquals("de.voci", meta.getString("package"))
        assertEquals("de.voci/de.voci.MainActivity", meta.getString("component"))
        assertEquals(1L, meta.getLong("version_code"))
        assertEquals("0.1.0", meta.getString("version_name"))
        assertEquals(ORIGINAL_APK, meta.getString("main_apk_sha256"))
        assertEquals(SEARCH, meta.getString("owned_search"))
        assertEquals(bytes.size, meta.getInt("payload_bytes"))
        assertEquals(hash(bytes), meta.getString("payload_sha256"))
        val legacy = decode(bytes)
        assertFalse("FIXTURE: original input must actually have missing marker", legacy.containsKey(R3gMigrationRunner.SCHEMA_KEY))
        runner.packet("legacy-input-read-for-replay", JSONObject().put("existing_pinned_input", existing.isNotEmpty())
            .put("payload_bytes", bytes.size).put("payload_sha256", hash(bytes)).put("metadata_sha256", hash(metaBytes))
            .put("source_marker", "missing").put("marker_origin", "original-legacy"))
        return legacy
    }

    private fun ownedDirectory(): File {
        val cache = app.cacheDir.canonicalFile
        val base = File(cache, "r3g-migration-probe")
        val original = File(base, "r3d-original")
        for (dir in listOf(base, original)) {
            assertFalse("FIXTURE: owned path must not be a symlink", Files.isSymbolicLink(dir.toPath()))
            assertEquals(dir.absolutePath, dir.canonicalPath)
            if (!Files.exists(dir.toPath(), NOFOLLOW_LINKS)) assertTrue(dir.mkdir())
            assertTrue(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS))
        }
        assertEquals("FIXTURE: no unrelated artifacts in owned probe", setOf(original.name), base.list()!!.toSet())
        return original
    }

    private fun writeNew(file: File, bytes: ByteArray) {
        Files.newOutputStream(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(bytes) }
    }

    private fun readBounded(file: File, limit: Int): ByteArray {
        assertTrue("FIXTURE: owned regular file required", Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        assertEquals(file.absolutePath, file.canonicalPath)
        assertTrue(file.length() in 1..limit.toLong())
        return Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                assertTrue("FIXTURE: growing input exceeds bound", out.size() + count <= limit)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private fun validHash(value: String?) = value?.matches(Regex("[0-9a-f]{64}")) == true
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
}
