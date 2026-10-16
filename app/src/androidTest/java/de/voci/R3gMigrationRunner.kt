package de.voci

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.runner.AndroidJUnitRunner
import org.json.JSONObject
import java.util.UUID

/** Opt-in, case-owned public lifecycle transport. No Android/navigation reflection or state surgery.
 * Fresh replay is NOT ActivityScenario.recreate(), OS process death, or retained-owner restoration.
 * Unarmed Activities and all other tests receive the superclass behavior without interception.
 */
class R3gMigrationRunner : AndroidJUnitRunner() {
    companion object {
        const val SCHEMA_KEY = "de.voci.navigation.schema"
        const val CURRENT_SCHEMA = 2
        const val STATUS_KEY = "r3g_migration"
        private const val CASE_TOKEN = "de.voci.test.r3g.case"
        fun identity(value: Any): String = Integer.toHexString(System.identityHashCode(value))
        fun marker(state: Bundle?): String = when {
            state == null -> "no-bundle"
            !state.containsKey(SCHEMA_KEY) -> "missing"
            state.getInt(SCHEMA_KEY, Int.MIN_VALUE) == CURRENT_SCHEMA -> "2"
            else -> "other"
        }
    }

    private var caseName: String? = null
    private var token: String? = null
    private var activity: Activity? = null
    private var captureTarget: Activity? = null
    private var pending: Bundle? = null
    private var injectionTarget: Activity? = null
    private var inputOrigin: String? = null
    private var sequence = 0
    private var injectionCount = 0
    private var createCount = 0
    private var saveCount = 0
    private var callbackFailed = false

    fun beginCase(name: String) {
        mainThread()
        checkFixture(caseName == null, "runner already owned")
        caseName = name
        token = UUID.randomUUID().toString()
        sequence = 0
        injectionCount = 0
        createCount = 0
        saveCount = 0
        callbackFailed = false
        packet("case-begin")
    }

    fun launchIntent(): Intent {
        mainThread()
        checkFixture(caseName != null && activity == null, "launch requires no live owned Activity")
        return Intent(targetContext, MainActivity::class.java).putExtra(CASE_TOKEN, token)
    }

    /** Called only after the source ActivityScenario was closed and its Activity destroyed. */
    fun armFreshReplay(state: Bundle, origin: String) {
        mainThread()
        checkFixture(caseName != null && activity == null && pending == null && injectionCount == 0,
            "fresh replay must be armed exactly once after source destruction")
        checkFixture(origin == "original-legacy" || origin == "injected-format2", "unknown replay origin")
        pending = state
        inputOrigin = origin
        packet("replay-armed", JSONObject().put("marker", marker(state)).put("marker_origin", origin))
    }

    /** The empty out-parameter becomes real framework state via the production superclass callback.
     * No empty/synthetic Bundle is ever returned as a legacy snapshot.
     */
    fun capture(source: MainActivity): Bundle {
        mainThread()
        checkFixture(source === activity && captureTarget == null, "capture must own source Activity")
        val out = Bundle()
        captureTarget = source
        try {
            callActivityOnSaveInstanceState(source, out)
            checkFixture(!out.isEmpty, "framework save produced no state")
            checkFixture(!callbackFailed, "save callback failed")
            return out
        } finally {
            captureTarget = null
        }
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        val created = super.newActivity(cl, className, intent)
        if (caseName != null && className == MainActivity::class.java.name &&
            intent.getStringExtra(CASE_TOKEN) == token) {
            checkFixture(activity == null, "unexpected concurrent owned Activity")
            activity = created
            if (pending != null) injectionTarget = created
            packet("instance-bound", JSONObject().put("activity", identity(created)))
        }
        return created
    }

    override fun callActivityOnCreate(target: Activity, savedInstanceState: Bundle?) {
        if (target !== activity) {
            super.callActivityOnCreate(target, savedInstanceState)
            return
        }
        val inject = target === injectionTarget
        val state = if (inject) pending else savedInstanceState
        val origin = if (inject) inputOrigin else "original-framework"
        val details = JSONObject().put("marker_origin", origin).put("injected", inject)
        boundary("create", target, details) {
            details.put("marker", marker(state))
            if (inject) {
                checkFixture(savedInstanceState == null && state != null, "replay launch was not fresh")
                checkFixture(injectionCount == 0, "duplicate injection")
                // Consume BEFORE entering product code; release all transport references immediately.
                pending = null
                injectionTarget = null
                inputOrigin = null
                injectionCount++
            }
            super.callActivityOnCreate(target, state)
            createCount++
        }
    }

    override fun callActivityOnSaveInstanceState(target: Activity, outState: Bundle) {
        if (target !== activity) {
            super.callActivityOnSaveInstanceState(target, outState)
            return
        }
        val details = JSONObject().put("capture", target === captureTarget)
            .put("marker_origin", "original-product-output")
        boundary("save", target, details) {
            super.callActivityOnSaveInstanceState(target, outState)
            details.put("marker", marker(outState))
            saveCount++
        }
    }

    override fun callActivityOnDestroy(target: Activity) {
        if (target !== activity) {
            super.callActivityOnDestroy(target)
            return
        }
        try {
            boundary("destroy", target, JSONObject()) { super.callActivityOnDestroy(target) }
        } finally {
            activity = null
            if (captureTarget === target) captureTarget = null
            if (injectionTarget === target) injectionTarget = null
        }
    }

    fun verifyTransport(expectedInjections: Int, minimumSaves: Int) {
        mainThread()
        checkFixture(!callbackFailed && pending == null && injectionTarget == null,
            "incomplete lifecycle transport")
        checkFixture(injectionCount == expectedInjections && createCount == 2 && saveCount >= minimumSaves,
            "lifecycle counts differ")
        packet("transport-verified", JSONObject().put("creates", createCount)
            .put("saves", saveCount).put("injections", injectionCount))
    }

    /** Call in the owning test's outermost finally, including setup/launch/callback failure paths. */
    fun endCase() {
        mainThread()
        try {
            if (caseName != null) packet("case-end", JSONObject().put("live_activity", activity != null)
                .put("pending_replay", pending != null).put("callback_failed", callbackFailed))
        } finally {
            activity = null
            captureTarget = null
            pending = null
            injectionTarget = null
            inputOrigin = null
            caseName = null
            token = null
            sequence = 0
            injectionCount = 0
            createCount = 0
            saveCount = 0
            callbackFailed = false
        }
    }

    @Synchronized fun packet(label: String, details: JSONObject = JSONObject()) {
        val name = caseName ?: throw AssertionError("FIXTURE: status outside owning case")
        checkFixture(sequence < 128, "sidechannel packet limit exceeded")
        val value = JSONObject().put("sequence", ++sequence).put("test", name)
            .put("label", label).put("details", details).toString()
        checkFixture(value.toByteArray(Charsets.UTF_8).size <= 4096, "sidechannel packet too large")
        sendStatus(2, Bundle().apply { putString(STATUS_KEY, value) })
    }

    private inline fun boundary(label: String, target: Activity, details: JSONObject, action: () -> Unit) {
        details.put("activity", identity(target))
        packet("$label-begin", details)
        try {
            action()
            packet("$label-end", details)
        } catch (failure: Throwable) {
            callbackFailed = true
            packet("$label-failed", JSONObject().put("activity", identity(target))
                .put("failure_type", failure.javaClass.simpleName))
            throw AssertionError("FIXTURE: $label lifecycle callback failed (${failure.javaClass.simpleName})")
        }
    }

    private fun mainThread() = checkFixture(Looper.myLooper() == Looper.getMainLooper(), "main thread required")
    private fun checkFixture(ok: Boolean, label: String) {
        if (!ok) throw AssertionError("FIXTURE: $label")
    }
}
