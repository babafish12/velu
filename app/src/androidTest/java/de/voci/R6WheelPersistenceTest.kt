package de.voci

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.LessonProvider
import de.voci.audio.AudioFiles
import de.voci.audio.PlaybackService
import de.voci.audio.PreparedSession
import de.voci.data.PreferencesStore
import de.voci.domain.LessonContent
import de.voci.domain.SessionProgress
import de.voci.domain.SessionSpec
import de.voci.domain.VocabItem
import de.voci.features.player.PlayerScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Source-only follow-ups to the frozen R6 wheel tracer. Parent compiles/runs on an idle service,
 * with foreign last-session files isolated and installed offline en-GB/de-DE voices.
 * No process-recreation claim: A removes the old composition/VM, Parent owns force-stop testing.
 * Only fixture-owned settings/artifacts change; no credentials, provider access or fake audio.
 */
class R6WheelPersistenceTest {
    @get:Rule val compose = createComposeRule()
    private val wheel = hasContentDescription("Eintragsrad")
    private val wheelTranslationFirst = booleanPreferencesKey("wheelTranslationFirst")

    @Test fun translatedWheelSurvivesNewViewModelAndLongTextAt200Percent() = fixture { f ->
        val initial = f.start()
        f.queue(initial, f.a, "R6 WHEEL PERSISTENCE PRODUCT")
        f.show(initial)
        val initialTarget = targetRow(f.a).performScrollToInPage()
        // Capture after both containers settle, immediately before the original assertion.
        // Keep that assertion primary even if capture also fails.
        val diagnosticFailure = try {
            captureInitialWheelLayout(f.app, f.a, initialTarget)
            null
        } catch (t: Throwable) {
            AssertionError("R6_LAYOUT_DIAG: initial wheel capture failed", t)
        }
        try {
            initialTarget.assertIsDisplayed().assertIsSelected()
        } catch (t: Throwable) {
            diagnosticFailure?.let { t.addSuppressed(it) }
            throw t
        }
        diagnosticFailure?.let { throw it }
        readableText(hasText(f.a.target) and hasAnyAncestor(wheel))
        readableText(hasText(f.b.target) and hasAnyAncestor(wheel))
        val player = compose.runOnIdle { PlaybackService.localPlayer!! }
        val before = compose.runOnIdle { queueSnapshot(player) }
        val syntheses = f.syntheses.get()
        val preparations = f.playbackCalls.get()

        swapControl().performClick()
        awaitStored(f, true)
        awaitLanguage(f, true)
        translatedSelection(f, f.a)
        // Persisted true must survive destroying the old VM and all screen state.
        f.replaceViewModel()
        awaitLanguage(f, true)
        translatedSelection(f, f.a)
        readableTranslatedRows(f)
        readableText(hasText(f.a.target) and !hasAnyAncestor(wheel))
        compose.runOnIdle {
            val state = f.app.playback.state.value
            assertSame("R6 WHEEL PERSISTENCE PRODUCT: player survives new VM", player, PlaybackService.localPlayer)
            assertSame("R6 WHEEL PERSISTENCE PRODUCT: exact session survives new VM", initial, state.session)
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: queue/index/position survive new VM", before, queueSnapshot(player))
            assertFalse("R6 WHEEL PERSISTENCE PRODUCT: paused intention survives", player.playWhenReady)
            assertFalse("R6 WHEEL PERSISTENCE PRODUCT: actual playback remains paused", player.isPlaying)
            assertFalse("R6 WHEEL PERSISTENCE PRODUCT: coordinator remains paused", state.playing)
            assertFalse("R6 WHEEL PERSISTENCE PRODUCT: no completion", state.ended)
            assertNull("R6 WHEEL PERSISTENCE PRODUCT: no error", state.error)
            assertTrue("R6 WHEEL PERSISTENCE PRODUCT: no completion navigation", f.completions.isEmpty())
        }
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: recreation/swap do not synthesize", syntheses, f.syntheses.get())
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: recreation/swap do not replan", preparations, f.playbackCalls.get())
        f.queue(initial, f.a, "R6 WHEEL PERSISTENCE PRODUCT")
        required(hasText("Play")).performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        swapControl() // Measured 48dp and reachable even after scrolling to transport at 200%.

        // Both labels are identical: pick the actual unselected semantic row, then verify ID B.
        required(translatedRow(f.b) and isNotSelected()).performScrollToInPage().assertIsDisplayed().performClick()
        val selected = f.selected(initial, f.b)
        translatedSelection(f, f.b)
        f.queue(selected, f.b, "R6 WHEEL PERSISTENCE PRODUCT")
        readableTranslatedRows(f)
        readableText(hasText(f.b.target) and !hasAnyAncestor(wheel))
        val selectedQueue = compose.runOnIdle { queueSnapshot(player) }
        swapControl().performClick()
        awaitStored(f, false)
        awaitLanguage(f, false)
        targetRow(f.b).performScrollToInPage().assertIsDisplayed().assertIsSelected()
        targetRow(f.a).assertIsNotSelected()
        readableText(hasText(f.b.target) and hasAnyAncestor(wheel))
        readableText(hasText(f.a.target) and hasAnyAncestor(wheel))
        readableText(hasText(f.b.translation) and !hasAnyAncestor(wheel))
        compose.runOnIdle {
            assertSame("R6 WHEEL PERSISTENCE PRODUCT: switching back retains selected session", selected, f.app.playback.state.value.session)
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: switching back retains queue", selectedQueue, queueSnapshot(player))
            assertFalse("R6 WHEEL PERSISTENCE PRODUCT: selection and switch keep paused intent", player.playWhenReady)
        }
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no provider access", 0, f.providerCalls.get())
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: lesson unchanged", f.lesson,
            runBlocking { f.app.repository.content(f.lesson.id) })
    }

    @Test fun playingLanguageSwapKeepsQueueAndRapidTogglesAreAtomic() = fixture(longPlaying = true) { f ->
        val session = f.start()
        f.queue(session, f.a, "R6 WHEEL PERSISTENCE PRODUCT")
        f.show(session, scale = 1f)
        val files = fileHashes(session, f)
        val syntheses = f.syntheses.get()
        val preparations = f.playbackCalls.get()
        val player = compose.runOnIdle { PlaybackService.localPlayer!! }
        required(hasText("Play")).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        eventually("FIXTURE: genuinely playing real long audio") { player.isPlaying && f.app.playback.state.value.playing }
        val discontinuities = AtomicInteger()
        val queueChanges = AtomicInteger()
        val pauses = AtomicInteger()
        val stopped = AtomicInteger()
        val ended = AtomicInteger()
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                discontinuities.incrementAndGet()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // SOURCE_UPDATE resolves duration/metadata; it is not a queue replacement.
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) queueChanges.incrementAndGet()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) pauses.incrementAndGet()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) stopped.incrementAndGet() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.incrementAndGet()
            }
        }
        val before = compose.runOnIdle {
            assertTrue("FIXTURE: observation begins during actual playback", player.isPlaying)
            assertEquals("FIXTURE: observation stays in the first real utterance", 0, player.currentMediaItemIndex)
            assertTrue("FIXTURE: at least 20s measured first-utterance runway, not a 2s ending race",
                player.duration - player.currentPosition >= 20_000)
            queueSnapshot(player).also { player.addListener(listener) }
        }
        val observer = compose.runOnIdle {
            f.owner.launch(start = CoroutineStart.UNDISPATCHED) {
                f.app.playback.state.collect { if (it.ended) ended.incrementAndGet() }
            }
        }
        var failure: Throwable? = null
        try {
            swapControl().performClick()
            awaitStored(f, true)
            awaitLanguage(f, true)
            translatedSelection(f, f.a)
            compose.runOnIdle {
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: German arrives while actually playing", player.isPlaying)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: play intention survives German swap", player.playWhenReady)
            }

            // Two public visual-toggle intents in ONE main-thread turn. No prefs collection,
            // manual bool write, clock advance or waiting between calls; join both actual writes.
            val toggles = compose.runOnIdle {
                listOf(f.vm.toggleWheelLanguage(), f.vm.toggleWheelLanguage())
            }
            runBlocking { withTimeout(8_000) { toggles.joinAll() } }
            awaitStored(f, true) // true -> false -> true, even before the UI flow can catch up.
            awaitLanguage(f, true)
            translatedSelection(f, f.a)
            eventually("R6 WHEEL PERSISTENCE PRODUCT: real playback advances after both persisted toggles", 5_000) {
                player.isPlaying && player.currentMediaItemIndex == before.index &&
                    player.currentPosition >= before.position + 1_000
            }
            compose.runOnIdle {
                val state = f.app.playback.state.value
                val after = queueSnapshot(player)
                assertSame("R6 WHEEL PERSISTENCE PRODUCT: actual player retained", player, PlaybackService.localPlayer)
                assertSame("R6 WHEEL PERSISTENCE PRODUCT: exact PreparedSession retained", session, state.session)
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: session ID retained", session.id, state.session?.id)
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: stable item ID retained", f.a.id, state.session?.singleItemId)
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: cues retained", session.cues, state.session?.cues)
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: MediaItems retained", before.items, after.items)
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: media IDs and URIs retained", before.identities, after.identities)
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no index reset/advance during observation", before.index, after.index)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: actual position advanced", after.position > before.position)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: playing intention retained", player.playWhenReady)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: actual audio playing", player.isPlaying)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: coordinator playing", state.playing)
                assertFalse("R6 WHEEL PERSISTENCE PRODUCT: not completed", state.ended)
                assertNull("R6 WHEEL PERSISTENCE PRODUCT: no playback error", state.error)
                assertNull("R6 WHEEL PERSISTENCE PRODUCT: no preference error", f.vm.error.value)
                assertNull("R6 WHEEL PERSISTENCE PRODUCT: no preparation", f.app.playback.preparing.value)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: no completion navigation", f.completions.isEmpty())
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no position discontinuity", 0, discontinuities.get())
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no playlist replacement", 0, queueChanges.get())
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no unexpected pause intent", 0, pauses.get())
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: never stopped playing", 0, stopped.get())
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no completion event", 0, ended.get())
            }
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no synthesis", syntheses, f.syntheses.get())
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no preparation/replan/write", preparations, f.playbackCalls.get())
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: no provider access", 0, f.providerCalls.get())
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: validated audio bytes retained", files, fileHashes(session, f))
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: lesson unchanged", f.lesson,
                runBlocking { f.app.repository.content(f.lesson.id) })
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            val listenerCleanup = runCatching { compose.runOnIdle { player.removeListener(listener) } }.exceptionOrNull()
            val observerCleanup = runCatching { runBlocking { observer.cancelAndJoin() } }.exceptionOrNull()
            val problems = listOfNotNull(listenerCleanup, observerCleanup)
            if (failure != null) problems.forEach { failure.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }

    /** No actions, extra scrolls, clock changes or explicit idle waits in this probe. */
    private fun captureInitialWheelLayout(app: VociApp, item: VocabItem, target: SemanticsNodeInteraction) {
        val directory = File(app.cacheDir, "r6-wheel-layout-probe")
        check(directory.isDirectory || directory.mkdirs()) { "R6_LAYOUT_DIAG: cannot create probe directory" }
        val attempt = "initial-target-${UUID.randomUUID()}"
        val textLimit = 100_000
        fun writeArtifact(suffix: String, limit: Int, write: (OutputStream) -> Unit) {
            val file = File(directory, "$attempt-$suffix")
            // CREATE_NEW also prevents overwriting in the event of a filename collision.
            Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { raw ->
                val bounded = object : OutputStream() {
                    var bytes = 0
                    override fun write(value: Int) {
                        check(bytes < limit) { "R6_LAYOUT_DIAG: artifact byte limit exceeded" }
                        raw.write(value)
                        bytes++
                    }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        check(length <= limit - bytes) { "R6_LAYOUT_DIAG: artifact byte limit exceeded" }
                        raw.write(buffer, offset, length)
                        bytes += length
                    }
                }
                write(bounded)
            }
            Log.i("R6_LAYOUT_DIAG", file.name)
        }
        fun writeText(suffix: String, value: String) {
            val bounded = if (value.length <= textLimit) value
                else value.take(textLimit - 32) + "\n[TRUNCATED: 100000 char limit]\n"
            writeArtifact(suffix, textLimit * 4) { it.write(bounded.toByteArray(Charsets.UTF_8)) }
        }

        // Full actual display, not a Compose-node crop or a reconstructed frame.
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "R6_LAYOUT_DIAG: takeScreenshot returned null"
        }
        val bitmapSize = "${bitmap.width}x${bitmap.height}"
        try {
            writeArtifact("display.png", 32 * 1024 * 1024) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "R6_LAYOUT_DIAG: PNG compression failed" }
            }
        } finally { bitmap.recycle() }

        // Suppress implicit waits only for diagnostic queries; original actions/assertions retain
        // their normal synchronization. Inspect nodes/range lambdas on the UI thread below.
        val (row, mergedRoots, unmergedRoots) = compose.runWithoutImplicitWait {
            Triple(target.fetchSemanticsNode(),
                compose.onAllNodes(isRoot()).fetchSemanticsNodes(),
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes())
        }
        val snapshots = compose.runOnUiThread {
            fun describe(node: SemanticsNode): String = buildString {
                val config = node.config
                append("id=${node.id} parent=${node.parent?.id} sizePx=${node.size}")
                append(" boundsInRoot=${node.boundsInRoot} boundsInWindow=${node.boundsInWindow}")
                append(" positionInRoot=${node.positionInRoot} positionOnScreen=${node.positionOnScreen}")
                append(" attached=${node.layoutInfo.isAttached} placed=${node.layoutInfo.isPlaced}")
                append(" density=${node.layoutInfo.density.density} fontScale=${node.layoutInfo.density.fontScale}")
                append(" selected=${config.getOrNull(SemanticsProperties.Selected)}")
                append(" ScrollBy=${config.contains(SemanticsActions.ScrollBy)}")
                append(" ScrollToIndex=${config.contains(SemanticsActions.ScrollToIndex)}")
                for ((name, key) in listOf("horizontal" to SemanticsProperties.HorizontalScrollAxisRange,
                    "vertical" to SemanticsProperties.VerticalScrollAxisRange)) {
                    val range = config.getOrNull(key)
                    append(" $name=")
                    if (range == null) append("absent")
                    else append("value:${range.value()},max:${range.maxValue()},reverse:${range.reverseScrolling}")
                }
                val coordinates = node.layoutInfo.coordinates
                var rootCoordinates = coordinates
                while (rootCoordinates.parentLayoutCoordinates != null) {
                    rootCoordinates = rootCoordinates.parentLayoutCoordinates!!
                }
                val clipped = rootCoordinates.localBoundingBoxOf(coordinates, clipBounds = true)
                val unclipped = rootCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
                append(" layoutClippedBoundsInRoot=$clipped layoutUnclippedBoundsInRoot=$unclipped")
                append(" effectiveLayoutClipping=${clipped != unclipped}")
            }
            val geometry = StringBuilder("Full display PNG pixels=$bitmapSize\n" +
                "Sequential screenshot/semantics observations; not an atomic frame. All geometry is in pixels.\n" +
                "Clipping compares actual layout coordinate bounds with clipBounds=true/false; " +
                "it does not assert an ancestor's own clip modifier flag.\n")
            fun tree(roots: List<SemanticsNode>, name: String): String {
                val output = StringBuilder("$name Compose semantics tree\n")
                var visited = 0
                fun visit(node: SemanticsNode, depth: Int) {
                    if (output.length >= textLimit) return
                    check(depth <= 128 && ++visited <= 2048) { "R6_LAYOUT_DIAG: semantics traversal limit exceeded" }
                    val config = node.config
                    val roles = buildList {
                        if (node.isRoot) add("root")
                        if (config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Eintragsrad") == true) add("wheel")
                        if (node.id == row.id) add("target-row")
                        if (config.getOrNull(SemanticsProperties.Text)?.any { it.text == item.target } == true) add("target-text")
                    }
                    val details = describe(node)
                    output.append("  ".repeat(depth)).append(details).append(" roles=$roles\n")
                    output.append("  ".repeat(depth)).append(config.toString()).append('\n')
                    if (roles.isNotEmpty()) geometry.append("$name $roles $details\n")
                    node.children.forEach { visit(it, depth + 1) }
                }
                roots.forEach { visit(it, 0) }
                return output.toString()
            }
            val merged = tree(mergedRoots, "merged")
            val unmerged = tree(unmergedRoots, "unmerged")
            geometry.append("Merged target row and ancestor chain, nearest first:\n")
            var ancestor: SemanticsNode? = row
            var depth = 0
            while (ancestor != null) {
                check(depth++ < 128) { "R6_LAYOUT_DIAG: ancestor traversal limit exceeded" }
                val node = ancestor
                val coordinates = node.layoutInfo.coordinates
                val rowClipped = coordinates.localBoundingBoxOf(row.layoutInfo.coordinates, clipBounds = true)
                val rowUnclipped = coordinates.localBoundingBoxOf(row.layoutInfo.coordinates, clipBounds = false)
                geometry.append(describe(node)).append(" targetBoundsInAncestorClipped=$rowClipped")
                    .append(" targetBoundsInAncestorUnclipped=$rowUnclipped")
                    .append(" effectiveTargetClipping=${rowClipped != rowUnclipped}\n")
                ancestor = node.parent
            }
            listOf("merged.txt" to merged, "unmerged.txt" to unmerged, "geometry.txt" to geometry.toString())
        }
        snapshots.forEach { (suffix, value) -> writeText(suffix, value) }
    }

    private fun swapControl() = required(hasContentDescription("Radsprache wechseln"))
        .performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

    private fun awaitStored(f: Fixture, expected: Boolean) {
        try { runBlocking { withTimeout(8_000) { f.settings.data.first { it[wheelTranslationFirst] == expected } } } }
        catch (e: TimeoutCancellationException) {
            throw AssertionError("R6 WHEEL PERSISTENCE PRODUCT: actual DataStore must contain $expected", e)
        }
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: exact persisted value", expected,
            runBlocking { f.settings.data.first()[wheelTranslationFirst] })
    }

    private fun awaitLanguage(f: Fixture, translated: Boolean) {
        eventually("R6 WHEEL PERSISTENCE PRODUCT: VM collects actual stored language") {
            f.vm.prefs.value.wheelTranslationFirst == translated
        }
        val state = if (translated) "Deutsch im Rad" else "Englisch im Rad"
        val matcher = hasContentDescription("Radsprache wechseln") and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)
        try { compose.waitUntil(8_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 } }
        catch (e: ComposeTimeoutException) {
            throw AssertionError("R6 WHEEL PERSISTENCE PRODUCT: displayed language must be $state", e)
        }
        swapControl().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
    }

    private fun translatedRow(item: VocabItem) =
        hasContentDescription("Eintrag auswählen: ${item.translation}") and hasAnyAncestor(wheel)

    private fun translatedSelection(f: Fixture, item: VocabItem) {
        assertEquals("FIXTURE: duplicate German labels", f.a.translation, f.b.translation)
        assertNotEquals("FIXTURE: distinct stable IDs", f.a.id, f.b.id)
        val rows = compose.onAllNodes(translatedRow(item)).fetchSemanticsNodes()
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: both duplicate labels retained", 2, rows.size)
        val selected = required(translatedRow(item) and isSelected())
        selected.performScrollToInPage().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: selected row keeps source identity despite duplicate labels",
            rows[f.lesson.items.indexOfFirst { it.id == item.id }].id, selected.fetchSemanticsNode().id)
        assertEquals("R6 WHEEL PERSISTENCE PRODUCT: only one selected row", 1,
            compose.onAllNodes(isSelected() and hasAnyAncestor(wheel)).fetchSemanticsNodes().size)
        compose.runOnIdle {
            val state = f.app.playback.state.value
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: actual selected stable item", item.id, state.session?.singleItemId)
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: actual active cue stable item", item.id,
                state.session?.cues?.getOrNull(state.index)?.itemId)
        }
    }

    private fun readableTranslatedRows(f: Fixture) {
        readableText(hasText(f.a.translation) and hasAnyAncestor(translatedRow(f.a) and isSelected()))
        readableText(hasText(f.a.translation) and hasAnyAncestor(translatedRow(f.a) and isNotSelected()))
    }

    private fun targetRow(item: VocabItem) = required(
        hasContentDescription("Eintrag auswählen: ${item.target}") and hasAnyAncestor(wheel))

    private fun required(matcher: SemanticsMatcher, unmerged: Boolean = false): SemanticsNodeInteraction {
        val count = compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().size
        if (count != 1) throw AssertionError("R6 WHEEL PERSISTENCE PRODUCT: expected one $matcher; found $count")
        return compose.onNode(matcher, useUnmergedTree = unmerged)
    }

    private data class PageScrollMeasurement(val pageId: Int, val delta: Float,
        val fullyVisible: Boolean, val geometry: String)

    /** performScrollTo aligns the nearest Lazy container, which can itself exceed the window. */
    private fun SemanticsNodeInteraction.performScrollToInPage(): SemanticsNodeInteraction {
        performScrollTo() // Preserve the original single nearest-container action.
        val maxOuterScrolls = 8
        for (attempt in 0..maxOuterScrolls) {
            compose.waitForIdle()
            val target = fetchSemanticsNode() // Never reuse geometry from before the last scroll.
            val measured = compose.runOnUiThread {
                val page = generateSequence(target.parent) { it.parent }.firstOrNull {
                    it.config.contains(SemanticsActions.ScrollBy) &&
                        !it.config.contains(SemanticsActions.ScrollToIndex) &&
                        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                } ?: throw AssertionError("FIXTURE: target has no outer scrollable Page ancestor")
                val coordinates = target.layoutInfo.coordinates
                val pageCoordinates = page.layoutInfo.coordinates
                assertTrue("FIXTURE: target and Page are attached and placed",
                    target.layoutInfo.isAttached && target.layoutInfo.isPlaced &&
                        page.layoutInfo.isAttached && page.layoutInfo.isPlaced)
                var root = coordinates
                while (root.parentLayoutCoordinates != null) root = root.parentLayoutCoordinates!!
                // The Page's unclipped layout includes its entire content. Use its clipped
                // viewport, intersected with the actual root, in the same coordinate space.
                val viewport = root.localBoundingBoxOf(pageCoordinates, clipBounds = true)
                    .intersect(root.localBoundingBoxOf(root, clipBounds = false))
                // boundsInRoot may be empty when the target is wholly outside the window.
                val rectangle = root.localBoundingBoxOf(coordinates, clipBounds = false)
                val clipped = root.localBoundingBoxOf(coordinates, clipBounds = true)
                val range = page.config[SemanticsProperties.VerticalScrollAxisRange]
                val geometry = "target=${target.id} rect=$rectangle clipped=$clipped " +
                    "Page=${page.id} viewport=$viewport scroll=${range.value()}/${range.maxValue()}"
                assertTrue("FIXTURE: nonempty target and Page viewport: $geometry",
                    rectangle.width > 0f && rectangle.height > 0f &&
                        viewport.width > 0f && viewport.height > 0f)
                assertTrue("FIXTURE: layout limit; target exceeds Page viewport: $geometry",
                    rectangle.width <= viewport.width && rectangle.height <= viewport.height)
                val fullyVisible = rectangle.left >= viewport.left && rectangle.right <= viewport.right &&
                    rectangle.top >= viewport.top && rectangle.bottom <= viewport.bottom && clipped == rectangle
                val distance = when {
                    rectangle.top < viewport.top -> rectangle.top - viewport.top
                    rectangle.bottom > viewport.bottom -> rectangle.bottom - viewport.bottom
                    else -> 0f
                }
                PageScrollMeasurement(page.id, if (range.reverseScrolling) -distance else distance,
                    fullyVisible, geometry)
            }
            if (measured.fullyVisible) return this
            if (attempt == maxOuterScrolls || measured.delta == 0f) {
                throw AssertionError("FIXTURE: outer Page scrolling did not fully expose target " +
                    "after $attempt actions (layout/scroll limit): ${measured.geometry}")
            }
            // Scope only this iteration's actual ancestor; never dispatch a gesture to EntryWheel.
            compose.onNode(SemanticsMatcher("measured outer Page ${measured.pageId}") {
                it.id == measured.pageId && it.config.contains(SemanticsActions.ScrollBy) &&
                    !it.config.contains(SemanticsActions.ScrollToIndex)
            }, useUnmergedTree = true).performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                assertTrue("FIXTURE: outer Page accepts measured scroll: ${measured.geometry}",
                    scroll(0f, measured.delta))
            }
        }
        throw AssertionError("FIXTURE: outer Page scroll attempt bound exhausted")
    }

    private fun readableText(matcher: SemanticsMatcher, scale: Float = 2f) {
        val node = required(matcher, unmerged = true).performScrollToInPage().assertIsDisplayed()
        val measuredSize = node.fetchSemanticsNode().size
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("R6 WHEEL PERSISTENCE PRODUCT: measured text layout", action(results))
        }
        assertTrue("R6 WHEEL PERSISTENCE PRODUCT: nonempty text layout", results.isNotEmpty())
        results.forEach { result ->
            assertEquals("R6 WHEEL PERSISTENCE PRODUCT: scoped font scale", scale, result.layoutInput.density.fontScale, 0f)
            assertTrue("R6 WHEEL PERSISTENCE PRODUCT: positive measured text size", result.size.width > 0 && result.size.height > 0)
            assertTrue("R6 WHEEL PERSISTENCE PRODUCT: layout fits actual measured Text width", result.size.width <= measuredSize.width + 1)
            assertTrue("R6 WHEEL PERSISTENCE PRODUCT: layout fits actual measured Text height", result.size.height <= measuredSize.height + 1)
            // Use actual line extents: paragraph layout width may exceed intrinsic Text width.
            assertTrue("R6 WHEEL PERSISTENCE PRODUCT: full text height fits", result.multiParagraph.height <= result.size.height + 1f)
            assertFalse("R6 WHEEL PERSISTENCE PRODUCT: no exceeded max lines", result.multiParagraph.didExceedMaxLines)
            for (line in 0 until result.lineCount) {
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: line left fits", result.getLineLeft(line) >= -1f)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: line right fits", result.getLineRight(line) <= minOf(result.size.width, measuredSize.width) + 1f)
                assertFalse("R6 WHEEL PERSISTENCE PRODUCT: no ellipsis", result.isLineEllipsized(line))
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: line top fits", result.getLineTop(line) >= -1f)
                assertTrue("R6 WHEEL PERSISTENCE PRODUCT: line bottom fits", result.getLineBottom(line) <= minOf(result.size.height, measuredSize.height) + 1f)
            }
        }
    }

    private data class QueueSnapshot(val items: List<MediaItem>, val identities: List<Pair<String, String?>>,
        val index: Int, val position: Long)

    private fun queueSnapshot(player: Player): QueueSnapshot {
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        return QueueSnapshot(items, items.map { it.mediaId to it.localConfiguration?.uri?.toString() },
            player.currentMediaItemIndex, player.currentPosition)
    }

    private fun fileHashes(session: PreparedSession, f: Fixture): Map<String, String> = runBlocking {
        val files = (session.cues + session.sourceCues).map { cue ->
            val asset = f.app.db.dao().audio(cue.key) ?: throw AssertionError("FIXTURE: missing real audio metadata")
            assertTrue("FIXTURE: real measured WAV and metadata", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
            File(asset.path)
        } + compose.runOnIdle {
            val player = PlaybackService.localPlayer!!
            (0 until player.mediaItemCount).map { File(requireNotNull(player.getMediaItemAt(it).localConfiguration).uri.path!!) }
        }
        files.distinctBy { it.path }.associate { it.path to AudioFiles.hash(it) }
    }

    private fun eventually(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { compose.runOnIdle(condition) } }
        catch (e: ComposeTimeoutException) { throw AssertionError("$label (timeout ${timeout}ms)", e) }
    }

    private inner class Fixture(val app: VociApp, val lesson: LessonContent, val settings: DataStore<Preferences>) {
        val a = lesson.items[1]
        val b = lesson.items[0]
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var store = ViewModelStore()
        val providerCalls = AtomicInteger()
        val syntheses = AtomicInteger()
        val playbackCalls = AtomicInteger()
        private fun newVm() = VociViewModel(app, SavedStateHandle(), object : LessonProvider {
            override suspend fun generate(spec: SessionSpec): LessonContent {
                providerCalls.incrementAndGet()
                throw AssertionError("FIXTURE: wheel must never generate or access a provider")
            }
        }).also { store.put("r6-wheel-persistence", it) }
        var vm by mutableStateOf(newVm())
        val visible = mutableStateOf(true)
        val completions = mutableListOf<String>()
        var shown = false

        fun start(): PreparedSession {
            runBlocking {
                for (locale in listOf("en-GB", "de-DE")) assertTrue("FIXTURE: installed offline voice $locale",
                    withTimeout(20_000) { app.tts.voices(locale) }.isNotEmpty())
            }
            val id = compose.runOnIdle {
                app.playback.start(lesson, SessionSpec(lesson.title, lesson.language, 1), 1f, singleItemId = a.id)
            }
            eventually("FIXTURE: real finite A preparation", 60_000) {
                assertNull("FIXTURE: real preparation error", app.playback.state.value.error)
                app.playback.preparing.value == null && app.playback.state.value.session?.id == id
            }
            eventually("FIXTURE: actual Media3 playback") { PlaybackService.localPlayer?.isPlaying == true }
            compose.runOnIdle { PlaybackService.localPlayer!!.pause() }
            eventually("FIXTURE: initial pause") { !PlaybackService.localPlayer!!.isPlaying && !app.playback.state.value.playing }
            return app.playback.state.value.session!!
        }

        fun show(session: PreparedSession, scale: Float = 2f) {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    if (visible.value) key(vm) { VociTheme(reduced = true) {
                        PlayerScreen(session.id, vm, {}, {}) { id, _ -> completions += id }
                    } }
                }
            }
            shown = true
            compose.waitForIdle()
        }
        fun replaceViewModel() {
            val previous = vm
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle() // Remove PlayerScreen, including its rememberSaveable state.
            compose.runOnIdle {
                store.clear()
                store = ViewModelStore()
                vm = newVm() // New SavedStateHandle, same application/real preferences store.
            }
            assertNotSame("R6 WHEEL PERSISTENCE PRODUCT: new ViewModel instance", previous, vm)
            assertSame("R6 WHEEL PERSISTENCE PRODUCT: same application preference store", app.preferences, vm.app.preferences)
            eventually("R6 WHEEL PERSISTENCE PRODUCT: new VM collects durable German preference") {
                vm.prefs.value.wheelTranslationFirst
            }
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
        }

        fun selected(initial: PreparedSession, item: VocabItem): PreparedSession {
            eventually("R6 WHEEL PERSISTENCE PRODUCT: accessible selection settles with original session and paused intention", 60_000) {
                val state = app.playback.state.value
                val player = PlaybackService.localPlayer!!
                assertNull("R6 WHEEL PERSISTENCE PRODUCT: selection error", state.error)
                assertEquals("R6 WHEEL PERSISTENCE PRODUCT: selection keeps original session", initial.id, state.session?.id)
                state.session?.singleItemId == item.id && state.session?.cues?.all { it.itemId == item.id } == true &&
                    app.playback.preparing.value == null && player.playbackState == Player.STATE_READY &&
                    !state.ended && !player.playWhenReady && !player.isPlaying && !state.playing
            }
            return app.playback.state.value.session!!
        }

        fun queue(session: PreparedSession, item: VocabItem, label: String) {
            assertEquals("$label: source lesson retained", lesson, session.lesson)
            assertEquals("$label: finite Single identity", item.id, session.singleItemId)
            assertFalse("$label: no forced recall", session.recall)
            assertEquals("$label: four cues", 4, session.cues.size)
            assertEquals("$label: four phases", listOf("target", "meaning", "repeat", "example"), session.cues.map { it.phase })
            val texts = mapOf("target" to item.target, "meaning" to item.translation, "repeat" to item.target, "example" to item.example)
            val assets = runBlocking { session.cues.map { cue ->
                assertEquals("$label: selected item", item.id, cue.itemId)
                assertEquals("$label: actual utterance mapping", texts[cue.phase], cue.text)
                val asset = app.db.dao().audio(cue.key) ?: throw AssertionError("$label: missing audio metadata")
                assertTrue("$label: measured duration", cue.durationMs > 0 && cue.durationMs == asset.durationMs)
                assertTrue("$label: validated real WAV", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
                asset
            } }
            compose.runOnIdle {
                val player = PlaybackService.localPlayer!!
                assertEquals("$label: actual four audio/pause pairs", 8, player.mediaItemCount)
                session.cues.forEachIndexed { i, cue ->
                    assertEquals("$label: Media3 audio identity", cue.key, player.getMediaItemAt(i * 2).mediaId)
                    assertEquals("$label: Media3 validated file", assets[i].path, player.getMediaItemAt(i * 2).localConfiguration?.uri?.toString())
                    assertEquals("$label: actual pause", "pause", player.getMediaItemAt(i * 2 + 1).mediaId)
                }
                assertEquals("$label: actual active item", item.id, session.cues[player.currentMediaItemIndex / 2].itemId)
            }
        }

    }

    private fun fixture(longPlaying: Boolean = false, body: (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        eventually("FIXTURE: local Media3 service connected") { app.playback; PlaybackService.localPlayer != null }
        runBlocking { withContext(Dispatchers.Main) { withTimeout(10_000) { app.playback.restore() } } }
        compose.runOnIdle {
            assertNull("FIXTURE: requires no restored session", app.playback.state.value.session)
            assertNull("FIXTURE: requires no pending preparation", app.playback.preparing.value)
            assertEquals("FIXTURE: requires empty Media3 queue", 0, PlaybackService.localPlayer!!.mediaItemCount)
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val spokenToken = token.take(8)
        // A uses long, distinct English rows and deliberately identical German labels for two IDs.
        // B's first real utterance is long enough to observe active swaps without natural cue endings.
        val meaning = "Bitte zeigen Sie uns den ruhigen Tisch am Fenster und bringen Sie die Speisekarte $spokenToken."
        val targetA = if (longPlaying)
            "Please listen carefully while I describe the quiet restaurant beside the station. " +
            "We would like a table near the window where we can talk about our journey and read the menu together. " +
            "After choosing our meal we will ask for some water and fresh bread, and then enjoy a relaxed conversation. " +
            "Before leaving we would like to thank the friendly staff, check the bill carefully and arrange another visit with our friends $spokenToken."
        else "Please show us the quiet table by the window and bring the evening menu $spokenToken."
        val lesson = LessonContent("r6-persistence-$token", "R6 persistence $token", "en-GB", listOf(
            VocabItem("r6-persistence-$token-b",
                "Could we sit beside the garden and look at the vegetarian dishes on your menu $spokenToken?",
                meaning, "We would like fresh bread and water $spokenToken.", "Wir möchten frisches Brot und Wasser $spokenToken."),
            VocabItem("r6-persistence-$token-a", targetA, meaning,
                "We will have a quiet meal by the window $spokenToken.", "Wir essen in Ruhe am Fenster $spokenToken.")
        ))
        val p = app.playback
        val playerParameters = compose.runOnIdle { PlaybackService.localPlayer!!.playbackParameters }
        val playWhenReady = compose.runOnIdle { PlaybackService.localPlayer!!.playWhenReady }
        val state = p.state.value
        val preparing = p.preparing.value
        val answers = p.answers.value
        val uncertain = p.uncertain.value
        val voiceInventory = app.tts.voiceInventory
        val ttsHook = app.tts.checkpoint
        val playbackHook = p.checkpoint
        val lastFile = File(app.filesDir, "last-session.json")
        val lastBytes = if (lastFile.exists()) lastFile.readBytes() else null
        val oldAudio = File(app.filesDir, "audio").list().orEmpty().toSet()
        val oldSilences = app.filesDir.list().orEmpty().filter { it.startsWith("silence-") }.toSet()
        val ownedKeys = Collections.synchronizedSet(mutableSetOf<String>())
        // Preserve every other setting; never clear or replace the preference store.
        @Suppress("UNCHECKED_CAST")
        val settings = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.preferences) as DataStore<Preferences>
        val settingsBefore = runBlocking { settings.data.first() }
        val markKey = stringSetPreferencesKey("uncertain.lesson.v1:${lesson.id}")
        assertNull("FIXTURE: UUID has no previous marks", settingsBefore[markKey])
        val delegates = listOf("auth\$delegate", "provider\$delegate", "http\$delegate").map { name ->
            val field = VociApp::class.java.getDeclaredField(name).apply { isAccessible = true }
            val original = field.get(app) as Lazy<*>
            Triple(field, original, original.isInitialized())
        }
        var f: Fixture? = null
        var failure: Throwable? = null
        try {
            runBlocking { settings.edit { it.remove(wheelTranslationFirst) } }
            assertNull("FIXTURE: absent preference exercises target-first default",
                runBlocking { settings.data.first()[wheelTranslationFirst] })
            app.tts.checkpoint = { stage, cue ->
                if (stage == "synthesized") f?.syntheses?.incrementAndGet()
                ttsHook(stage, cue)
                if (stage == "renamed" && lesson.items.any { it.id == cue.itemId }) {
                    File(app.filesDir, "audio").listFiles().orEmpty()
                        .filter { it.name !in oldAudio && it.extension == "wav" && !it.name.endsWith(".part.wav") }
                        .forEach { ownedKeys.add(it.nameWithoutExtension) }
                }
            }
            p.checkpoint = { stage, id ->
                f?.playbackCalls?.incrementAndGet()
                playbackHook(stage, id)
            }
            runBlocking {
                assertNull("FIXTURE: unique lesson", app.db.dao().lesson(lesson.id))
                app.repository.save(lesson)
            }
            val active = compose.runOnIdle { Fixture(app, lesson, settings) }
            f = active
            val prefs = runBlocking { app.preferences.flow.first() }
            eventually("FIXTURE: VM preferences collected") { active.vm.prefs.value == prefs }
            body(active)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            val unmount = runCatching {
                f?.takeIf { it.shown }?.let { active ->
                    compose.runOnIdle { active.visible.value = false }
                    compose.waitForIdle()
                }
            }.exceptionOrNull()
            val cleanup = runCatching {
                runBlocking {
                    withContext(Dispatchers.Main) {
                        withTimeout(15_000) {
                            val draining = p.cancelPreparation()
                            f?.vm?.cancel()
                            f?.store?.clear()
                            draining?.join()
                            // Stop observation of the test queue before draining its outstanding DB writes.
                            p.state.value = state
                            PlaybackService.localPlayer!!.let { it.pause(); it.clearMediaItems() }
                            @Suppress("UNCHECKED_CAST")
                            val writes = p.javaClass.getDeclaredField("progressWrites").apply { isAccessible = true }
                                .get(p) as MutableSet<Job>
                            writes.toList().joinAll()
                            app.db.dao().deleteLesson(lesson.id)
                            for (entry in app.db.dao().progressEntries()) {
                                val progress = runCatching { Json.decodeFromString<SessionProgress>(entry.json) }.getOrNull()
                                if (progress?.lessonId == lesson.id) app.db.dao().removeProgress(entry.sessionId)
                            }
                            File(app.filesDir, "plans/${lesson.id}.json").delete()
                            if (lastBytes == null) lastFile.delete() else lastFile.writeBytes(lastBytes)
                            for (key in ownedKeys.toList()) {
                                val asset = app.db.dao().audio(key)
                                if (asset != null) { File(asset.path).delete(); app.db.dao().removeAudio(key) }
                                else File(app.filesDir, "audio/$key.wav").delete()
                            }
                            app.filesDir.listFiles().orEmpty().filter {
                                it.name.startsWith("silence-") && it.name.endsWith(".wav") && it.name !in oldSilences
                            }.forEach { it.delete() }
                        }
                    }
                }
            }.exceptionOrNull()
            // Preference restoration is independent of artifact cleanup, including on expected RED.
            val preferencesRestore = runCatching {
                runBlocking {
                    settings.edit { values ->
                        values.remove(markKey)
                        val previous = settingsBefore[wheelTranslationFirst]
                        if (previous == null) values.remove(wheelTranslationFirst)
                        else values[wheelTranslationFirst] = previous
                    }
                    assertEquals("FIXTURE: all settings preserved", settingsBefore, settings.data.first())
                }
            }.exceptionOrNull()
            // Exact flow values and delegates restored even when body or cleanup fails.
            val stateRestore = runCatching {
                compose.runOnIdle {
                    f?.owner?.cancel()
                    f?.store?.clear()
                    app.tts.voiceInventory = voiceInventory
                    app.tts.checkpoint = ttsHook
                    p.checkpoint = playbackHook
                    p.state.value = state
                    p.preparing.value = preparing
                    p.answers.value = answers
                    p.uncertain.value = uncertain
                    PlaybackService.localPlayer!!.let {
                        it.playbackParameters = playerParameters
                        it.playWhenReady = playWhenReady
                    }
                }
            }.exceptionOrNull()
            val isolation = runCatching {
                delegates.forEach { (field, original, initialized) ->
                    assertSame("FIXTURE: original delegate preserved", original, field.get(app))
                    assertEquals("FIXTURE: no auth/provider/http initialization", initialized, original.isInitialized())
                }
            }.exceptionOrNull()
            val problems = listOfNotNull(unmount, cleanup, preferencesRestore, stateRestore, isolation)
            if (failure != null) problems.forEach { failure.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }
}
