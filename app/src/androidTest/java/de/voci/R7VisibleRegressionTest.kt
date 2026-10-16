package de.voci

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener

/** Explicit opt-in: AndroidJUnitRunner -e listener de.voci.R7VisibleRegressionListener.
 * Requires the existing debug Compose ComponentActivity host, serial worker-thread callbacks,
 * and runner 1.7.0's DEFAULT listener order (newRunListenerMode absent/false). In new order,
 * ActivityFinisherRunListener.testStarted would destroy our just-launched host before the test.
 * No runner/manifest changes, extra test methods, authorization grants or data cleanup.
 *
 * Parent must require READY/CLOSED markers for every selected whitelist case and reject any
 * R7_VISIBLE_FIXTURE_FAILURE / negative instrumentation status / JUnit mechanism failure.
 * Throwing an Exception from a RunListener is reported by JUnit 4.13.2 as TEST_MECHANISM;
 * explicit status reporting also preserves the original case identity for fail-closed parsing.
 */
class R7VisibleRegressionListener : RunListener() {
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var owner: Description? = null

    override fun testStarted(description: Description) {
        if (identity(description) !in allowedCases) return
        fixture(description, "start") {
            requireWorkerThread()
            check(!InstrumentationRegistry.getArguments().getString("newRunListenerMode").toBoolean()) {
                "runner default listener order is required; omit newRunListenerMode or set false"
            }
            check(scenario == null && owner == null) { "previous case still owns its scenario" }
            owner = description
            val launched = ActivityScenario.launch(ComponentActivity::class.java)
            scenario = launched
            check(launched.state == Lifecycle.State.RESUMED) { "scenario did not reach RESUMED" }
            launched.onActivity { activity ->
                check(Looper.myLooper() == Looper.getMainLooper()) { "lifecycle proof must run on main" }
                check(activity.javaClass == ComponentActivity::class.java) { "unexpected Activity host" }
                check(activity.lifecycle.currentState == Lifecycle.State.RESUMED) { "Activity is not RESUMED" }
                val app = activity.application as VociApp
                check(app === InstrumentationRegistry.getInstrumentation().targetContext.applicationContext) {
                    "host belongs to another application"
                }
                check(app.visible) { "application lifecycle has not established actual visibility" }
            }
            marker("READY", description, "RESUMED visible=true")
        }
    }

    override fun testFinished(description: Description) {
        if (identity(description) !in allowedCases) return
        fixture(description, "finish") {
            check(owner == null || identity(checkNotNull(owner)) == identity(description)) { "scenario owner mismatch" }
            closeOwned()
        }
    }

    override fun testRunFinished(result: Result) {
        val unfinished = owner ?: return
        fixture(unfinished, "run-finish") {
            closeOwned()
            error("owned scenario survived until testRunFinished; testFinished cleanup was missing")
        }
    }

    private fun closeOwned() {
        val owned = scenario
        val description = owner
        if (owned != null) {
            requireWorkerThread()
            owned.close()
            check(owned.state == Lifecycle.State.DESTROYED) { "owned scenario did not reach DESTROYED" }
        }
        scenario = null
        owner = null
        if (owned != null && description != null) marker("CLOSED", description, "DESTROYED")
    }

    private fun fixture(description: Description, phase: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            // Retry only our own scenario, never finish arbitrary Activities or alter app state.
            try { closeOwned() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            val reported = IllegalStateException(
                "R7_VISIBLE_FIXTURE_FAILURE ${identity(description)} $phase", failure
            )
            try {
                InstrumentationRegistry.getInstrumentation().sendStatus(-2, Bundle().apply {
                    putString("class", description.className)
                    putString("test", description.methodName)
                    putString("stack", reported.stackTraceToString())
                    putString("stream", "\n${reported.message}\n")
                    putString("r7_visible_fixture", "FAILURE")
                })
            } catch (reporting: Throwable) { reported.addSuppressed(reporting) }
            throw reported
        }
    }

    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ActivityScenario launch/close requires a non-main runner thread" }
    }

    private fun marker(state: String, description: Description, proof: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nR7_VISIBLE_FIXTURE_$state ${identity(description)} $proof\n")
            putString("r7_visible_fixture", state)
        })
    }

    private fun identity(description: Description) = "${description.className}#${description.methodName}"

    private companion object {
        // Exact 27 identities classified sichtbare_activity_fixture_erforderlich in
        // See docs/TESTING.md for the 71 historical test identities.
        // No class-level/prefix matching, parameter suffix stripping or seven retired cases.
        val allowedCases = setOf(
            "de.voci.R4PlaybackEdgeTest#pendingUncachedSelectionSurvivesOldItemsNaturalEnd",
            "de.voci.R4PlaybackEdgeTest#cachedFullCoursePlaysWithEmptyVoiceInventory_positiveControl",
            "de.voci.R4PlaybackEdgeTest#cachedSingleStartNeedsNoVoiceInventory",
            "de.voci.R4PlaybackEdgeTest#cachedSingleSelectionNeedsNoVoiceInventory",
            "de.voci.R4PlaybackEdgeTest#repeatUncertainAfterSingleSwitchReplaysMarkedAInsteadOfB",
            "de.voci.CorrectionPlaybackTest#savedThreeMinuteReplayDoesNotRewriteOriginalAtOneMinute",
            "de.voci.CorrectionPlaybackTest#deletingPendingPreparationCannotPublishSessionOrPlan",
            "de.voci.CorrectionPlaybackTest#deleteDuringRenderedFileCannotLeaveNewMetadataOrFiles",
            "de.voci.CorrectionPlaybackTest#deleteAfterAudioRenameCannotLeaveOrphanFile",
            "de.voci.CorrectionPlaybackTest#deleteDuringPlanPersistenceCannotLeaveSessionOrPlan",
            "de.voci.CorrectionPlaybackTest#staleRestoreCannotReplaceExplicitStart",
            "de.voci.CorrectionPlaybackTest#cancellationAfterDatabaseSaveBeforePublicationRollsBackNewLesson",
            "de.voci.CorrectionPlaybackTest#cancelledGenerationCannotSaveNavigateOrClearSuccessorBusy",
            "de.voci.PlaybackBoundaryTest#earlierRepositoryReadCannotStartDeletedLesson",
            "de.voci.PlaybackBoundaryTest#saveActionCannotPinSuccessorSession",
            "de.voci.PlaybackBoundaryTest#liveBoundaryDuringReplanCannotRestartCurrentSpokenFile",
            "de.voci.PlaybackBoundaryTest#staleReplanCannotOverwriteNewSessionFile",
            "de.voci.PlaybackBoundaryTest#staleReplanCannotResurrectDeletedSessionFile",
            "de.voci.R2MigrationDeviceTest#legacyColdStartCannotInvokeProviderOrModifyLibrary",
            "de.voci.R2MigrationDeviceTest#savedPlaybackReadBypassesGenerationGate",
            "de.voci.RemainingBudgetTest#speedDuringPrebufferCannotAppendStaleControllerPlaylist",
            "de.voci.SavedRecallTest#recallOptInAndOutDoNotRewriteSavedAudioPlan",
            "de.voci.R5WheelEdgeTest#recoverableSelectionErrorAllowsUnchangedQueueNaturalCompletion",
            "de.voci.R5WheelEdgeTest#committedSelectionSurvivesNoOpDragBeforePersistence",
            "de.voci.R5WheelEdgeTest#committedSelectionSurvivesCancelledDragBeforePersistence",
            "de.voci.R5WheelEdgeTest#ordinaryCommittedSelectionPersists_positiveControl",
            "de.voci.R5RejectedSelectionReplayTest#repeatAfterRejectedSelectionAllowsNaturalCompletion"
        )
    }
}
