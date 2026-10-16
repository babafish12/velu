package de.voci

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.PlaybackService
import de.voci.audio.PlaybackState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Requires Parent's opt-in R3gMigrationRunner APK. The immutable migration runner and its
 * three old tests are not changed. The process probe is separately selected, never run as an
 * unattended ordinary suite: Parent performs a real kill/resume in the SAME retained task.
 */
class R3gCurrentFormatAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val runner get() = instrumentation as? R3gMigrationRunner
        ?: throw AssertionError("FIXTURE: requires separate opt-in R3gMigrationRunner test APK")
    private val app get() = instrumentation.targetContext.applicationContext as VociApp
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test fun productEmitsSchema2_andOrdinaryActivityRecreationRestoresDrafts() {
        val name = "productEmitsSchema2_andOrdinaryActivityRecreationRestoresDrafts"
        var scenario: ActivityScenario<MainActivity>? = null
        var classification = "fixture-incomplete"
        main { runner.beginCase(name) }
        try {
            prerequisite()
            scenario = ActivityScenario.launch<MainActivity>(main { runner.launchIntent() })
            compose.waitForIdle()
            compose.onNode(hasText("Sprint") and heading).assertIsDisplayed()
            var before: MainActivity? = null
            scenario.onActivity { before = it }
            val old = requireNotNull(before)
            val vm = main { existingViewModel(old) }
            // All edits are transient UI drafts. No preference/filter persistence is introduced.
            compose.onNode(hasSetTextAction()).performTextReplacement("R3G1 ordinary Sprint draft")
            tab("Bibliothek")
            compose.onNodeWithText("Suchen").performTextReplacement("R3G1 ordinary Library draft")
            compose.onNodeWithText("IT").performClick()
            compose.waitForIdle()
            val original = main { runner.capture(old) }
            assertEquals("SCHEMA PRODUCT: actual product-emitted marker", "2", R3gMigrationRunner.marker(original))
            runner.packet("product-marker-asserted", JSONObject().put("marker_origin", "original-product-output")
                .put("marker", "2").put("injected", false))
            scenario.recreate()
            compose.waitForIdle()
            var after: MainActivity? = null
            scenario.onActivity { after = it }
            val current = requireNotNull(after)
            assertNotSame("SCHEMA PRODUCT: real Activity replacement", old, current)
            assertEquals(Lifecycle.State.DESTROYED, main { old.lifecycle.currentState })
            assertSame("SCHEMA PRODUCT: configuration recreation retains VM", vm, main { existingViewModel(current) })
            assertLibrary("R3G1 ordinary Library draft")
            main {
                assertEquals("SCHEMA PRODUCT: exact retained query", "R3G1 ordinary Library draft", vm.query.value)
                assertEquals("SCHEMA PRODUCT: exact retained filter", "it-IT", vm.filter.value)
            }
            tab("Sprint")
            compose.onNode(hasSetTextAction()).assertTextContains("R3G1 ordinary Sprint draft")
            assertEquals("SCHEMA PRODUCT: recreated product emits schema2 again", "2",
                R3gMigrationRunner.marker(main { runner.capture(current) }))
            main { runner.verifyTransport(expectedInjections = 0, minimumSaves = 2) }
            guardPlayback()
            classification = "assertions-passed"
        } catch (failure: Throwable) {
            classification = classify(failure)
            throw failure
        } finally {
            try {
                scenario?.close()
                guardPlayback()
                runner.packet("case-result", JSONObject().put("classification", classification)
                    .put("scope", "actual marker emission and ordinary Activity recreation; NOT process death"))
            } finally { main { runner.endCase() } }
        }
    }

    /** Parent protocol (handoff gives exact sequence): seed the named drafts/filter through
     * normal UI, background until STOPPED, record PID/task, kill the actual process without
     * force-stop or task removal, start this case, then resume that existing task AFTER READY.
     * No ActivityScenario, injected Bundle, fake process death, or durable fixture file here.
     */
    @Test fun currentSchemaRestoresAfterRealProcessDeath_parentCoordinated() {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("FIXTURE: explicit same-AVD kill/task attestation required", "true", args.getString("r3gRealProcessRestore"))
        assertEquals("FIXTURE: no force-stop/task replacement/data reset", "true", args.getString("r3gRetainedTaskAttested"))
        val previousPid = args.getString("r3gPreviousPid")?.toIntOrNull()
            ?: throw AssertionError("FIXTURE: missing previous PID")
        val expectedTask = args.getString("r3gRetainedTaskId")?.toIntOrNull()
            ?: throw AssertionError("FIXTURE: missing retained task ID")
        assertTrue("FIXTURE: valid independently recorded process/task", previousPid > 0 && expectedTask >= 0)
        assertNotEquals("FIXTURE: this must be a different OS process", previousPid, Process.myPid())
        assertTrue("FIXTURE: pre-create lifecycle observation requires API29+", Build.VERSION.SDK_INT >= 29)
        prerequisite()
        var activity: MainActivity? = null
        var marker: String? = null
        var creates = 0
        var resumes = 0
        var classification = "fixture-incomplete"
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(target: Activity, state: Bundle?) {
                if (target is MainActivity) {
                    creates++
                    marker = R3gMigrationRunner.marker(state)
                }
            }
            override fun onActivityCreated(target: Activity, state: Bundle?) { if (target is MainActivity) activity = target }
            override fun onActivityResumed(target: Activity) { if (target === activity) resumes++ }
            override fun onActivityStarted(target: Activity) {}
            override fun onActivityPaused(target: Activity) {}
            override fun onActivityStopped(target: Activity) {}
            override fun onActivitySaveInstanceState(target: Activity, state: Bundle) {}
            override fun onActivityDestroyed(target: Activity) {}
        }
        main { app.registerActivityLifecycleCallbacks(observer) }
        try {
            status("READY-resume-retained-task", JSONObject().put("pid", Process.myPid()).put("previous_pid", previousPid)
                .put("retained_task", expectedTask).put("deadline_ms", 30000))
            // Parent must resume via Recents/retained task, not launch a fresh Activity/clear task.
            compose.waitUntil(30000) { main { activity != null && resumes == 1 } }
            compose.waitForIdle()
            val restored = main { requireNotNull(activity) }
            main {
                assertEquals("PROCESS PRODUCT: exactly one real Activity create", 1, creates)
                assertEquals("PROCESS PRODUCT: saved schema2 supplied by framework", "2", marker)
                assertEquals("PROCESS PRODUCT: original task retained", expectedTask, restored.taskId)
                assertEquals("PROCESS PRODUCT: resumed Activity", Lifecycle.State.RESUMED, restored.lifecycle.currentState)
                val vm = existingViewModel(restored)
                assertEquals("PROCESS PRODUCT: Library query restored in new process", "R3G1 process Library draft", vm.query.value)
                assertEquals("PROCESS PRODUCT: filter restored in new process", "it-IT", vm.filter.value)
                assertEquals("PROCESS PRODUCT: inactive Sprint draft restored", "R3G1 process Sprint draft", vm.topic.value)
            }
            assertLibrary("R3G1 process Library draft")
            tab("Sprint")
            compose.onNode(hasSetTextAction()).assertTextContains("R3G1 process Sprint draft")
            tab("Bibliothek")
            assertLibrary("R3G1 process Library draft")
            guardPlayback()
            classification = "assertions-passed"
        } catch (failure: Throwable) {
            classification = classify(failure)
            throw failure
        } finally {
            main { app.unregisterActivityLifecycleCallbacks(observer) }
            // This Activity/task belongs to Parent; never finish or replace it in cleanup.
            guardPlayback()
            status("case-result", JSONObject().put("classification", classification)
                .put("observed_creates", main { creates }).put("observed_resumes", main { resumes })
                .put("activity_finished_by_fixture", false))
        }
    }

    private fun classify(failure: Throwable): String = when {
        failure is AssertionError && (failure.message.orEmpty().startsWith("SCHEMA PRODUCT:") ||
            failure.message.orEmpty().startsWith("PROCESS PRODUCT:")) -> "product-assertion-failed"
        failure is AssertionError && failure.message.orEmpty().startsWith("FIXTURE:") -> "fixture-failed"
        failure is AssertionError -> "ui-assertion-failed"
        else -> "fixture-error"
    }

    private fun prerequisite() {
        // Force runner prerequisite before any product initialization, including process probe.
        runner
        assertEquals("FIXTURE: Parent completed-baseline prerequisite", "true",
            InstrumentationRegistry.getArguments().getString("r3gParentCompletedBaseline"))
        main { app.playback }
        compose.waitUntil(10000) { main { PlaybackService.localPlayer != null } }
        guardPlayback()
    }
    private fun guardPlayback() = main {
        val player = PlaybackService.localPlayer ?: throw AssertionError("FIXTURE: actual Media3 must be ready")
        assertEquals("FIXTURE: never clear playlist", 0, player.mediaItemCount)
        assertFalse("FIXTURE: no playback", player.isPlaying)
        assertFalse("FIXTURE: no pending play intent", player.playWhenReady)
        assertEquals("FIXTURE: exact empty playback", PlaybackState(), app.playback.state.value)
        assertNull("FIXTURE: no preparation", app.playback.preparing.value)
        assertTrue("FIXTURE: no answers/uncertainty mutation", app.playback.answers.value.isEmpty() && app.playback.uncertain.value.isEmpty())
    }
    private fun existingViewModel(activity: MainActivity): VociViewModel = ViewModelProvider(activity,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                throw AssertionError("FIXTURE: production ViewModel must already exist")
        })[VociViewModel::class.java]
    private fun tab(label: String) {
        val matcher = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(matcher).assertCountEquals(1)
        compose.onNode(matcher).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
    }
    private fun assertLibrary(query: String) {
        compose.onNode(hasText("Bibliothek") and heading).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains(query)
        compose.onNodeWithText("IT").assertIsSelected()
    }
    private fun status(label: String, details: JSONObject) {
        instrumentation.sendStatus(2, Bundle().apply {
            putString("r3g_current_format", JSONObject().put("label", label).put("details", details).toString())
        })
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}
