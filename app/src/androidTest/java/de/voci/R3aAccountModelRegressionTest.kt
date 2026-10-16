package de.voci

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.ChatGptProvider
import de.voci.ai.ModelOption
import de.voci.auth.AuthManager
import de.voci.auth.CredentialStore
import de.voci.auth.VaultState
import de.voci.domain.ProviderState
import de.voci.features.settings.AccountScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** UI semantics only: no real identity, authorization, catalog request, or TalkBack speech. */
class R3aAccountModelRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val models = listOf(ModelOption("r3a-synthetic-alpha", "R3A Testmodell Alpha"), ModelOption("r3a-synthetic-beta", "R3A Testmodell Beta"))
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test fun everySelectableRadioOwnsExactlyItsModelNameRoleStateAndAction() = withAccount { vm ->
        assertSelection(vm, 0)
        // Both trees: no separately actionable, unnamed child RadioButton may remain.
        for (unmerged in listOf(false, true)) {
            val nodes = compose.onAllNodes(radio, useUnmergedTree = unmerged).fetchSemanticsNodes()
            report("radio-semantics-unmerged=$unmerged", nodes)
            assertEquals("F03 PRODUCT: exactly one radio per model", models.size, nodes.size)
            models.forEachIndexed { index, model ->
                val matches = nodes.filter { names(it) == listOf(model.label) }
                assertEquals("F03 PRODUCT: selectable radio must own exactly '${model.label}', not just have a nearby text", 1, matches.size)
                val node = matches.single()
                assertEquals(Role.RadioButton, node.config[SemanticsProperties.Role])
                assertEquals("F03 PRODUCT: named radio selection", index == 0, node.config.getOrNull(SemanticsProperties.Selected))
                assertNotNull("F03 PRODUCT: named radio click action", node.config.getOrNull(SemanticsActions.OnClick)?.action)
            }
        }
    }

    @Test fun touchingEachModelLabelSelectsExactlyThatModelAndDeselectsTheOther() = withAccount { vm ->
        assertSelection(vm, 0)
        for (index in listOf(1, 0, 1)) {
            val label = compose.onNodeWithText(models[index].label, useUnmergedTree = true)
            label.performScrollTo().assertIsDisplayed()
            // Real hit point on Text, not performClick() on a non-actionable sibling.
            label.performTouchInput { click(center) }
            compose.waitForIdle()
            report("label-touch-${models[index].slug}", compose.onAllNodes(radio).fetchSemanticsNodes())
            assertSelection(vm, index)
        }
    }

    @Test fun directRadioActionsChangeSelectionBothWays_positiveControl() = withAccount { vm ->
        assertSelection(vm, 0)
        for (index in listOf(1, 0, 1)) {
            compose.onNodeWithText(models[index].label, useUnmergedTree = true).performScrollTo()
            // R2 control deliberately identifies rows by stable rendered order, not missing labels.
            compose.onAllNodes(radio)[index].performClick()
            compose.waitForIdle()
            assertSelection(vm, index)
        }
    }

    private fun assertSelection(vm: VociViewModel, selected: Int) {
        compose.runOnIdle {
            assertEquals("F03 PRODUCT: label/control must select its own model", models[selected].slug, vm.selectedModel.value)
            assertEquals("F03 PRODUCT: provider must receive exactly that model", models[selected].slug, vm.app.provider.selectedModel)
        }
        val nodes = compose.onAllNodes(radio).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        assertEquals("FIXTURE: two model rows required", 2, nodes.size)
        assertEquals("F03 PRODUCT: mutually exclusive selection", listOf(selected == 0, selected == 1),
            nodes.map { it.config.getOrNull(SemanticsProperties.Selected) })
    }

    private fun names(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private class EmptyMemoryCredentials : CredentialStore {
        private var value = VaultState(host = "urn:r3a:synthetic-empty-host", accounts = emptyList(), active = null)
        override fun read() = value
        override fun write(state: VaultState) {
            check(state.accounts.isEmpty() && state.active == null) { "FIXTURE: credentials are forbidden" }
            value = state
        }
    }

    private fun withAccount(body: (VociViewModel) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        val requests = AtomicInteger(0)
        val http = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("R3A fixture forbids every HTTP request") // Never proceed, resolve DNS, or log a URL/header.
        }.build()
        // Existing vm.model() always dereferences app.provider. Reading an uninitialized production
        // delegate would construct app.auth/SecureVault. Preserve that delegate WITHOUT evaluating it.
        // Reflection is test-only wiring, pinned to unchanged R2; no auth/Keystore field is inspected.
        var field: java.lang.reflect.Field? = null
        var originalDelegate: Lazy<ChatGptProvider>? = null
        var originalInitialized = false
        var originalSelection: String? = null
        var installed = false
        var contentInstalled = false
        try {
            val auth = AuthManager(null, http, owner, EmptyMemoryCredentials())
            val providerField = VociApp::class.java.getDeclaredField("provider\$delegate").apply { isAccessible = true }
            field = providerField
            @Suppress("UNCHECKED_CAST")
            val savedDelegate = providerField.get(app) as Lazy<ChatGptProvider>
            originalDelegate = savedDelegate
            originalInitialized = savedDelegate.isInitialized()
            originalSelection = if (originalInitialized) savedDelegate.value.selectedModel else null
            val isolatedProvider = lazy { ChatGptProvider(http, auth) }
            compose.runOnIdle { providerField.set(app, isolatedProvider); installed = true }
            val vm = compose.runOnIdle {
                auth.state.value = ProviderState.PLAN_AUTHORIZED // Synthetic DISPLAY state only; vault stays empty.
                VociViewModel(app, SavedStateHandle()).also {
                    store.put("r3a-account", it)
                    it.models.value = models
                    it.model(models.first().slug)
                }
            }
            // Wait for the existing read-only preference collector before any hit-point test;
            // removing the legacy-provider notice later must not move the label during a tap.
            val durablePreferences = runBlocking { app.preferences.flow.first() }
            compose.waitUntil(5000) { vm.prefs.value == durablePreferences }
            compose.setContent {
                if (visible.value) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
                        VociTheme(reduced = true) { AccountScreen(vm, {}, auth) }
                    }
                }
            }
            contentInstalled = true
            compose.waitForIdle()
            models.forEach { compose.onNodeWithText(it.label, useUnmergedTree = true).assertExists() }
            body(vm)
        } finally {
            try {
                if (contentInstalled) {
                    compose.runOnIdle { visible.value = false }
                    compose.waitForIdle()
                }
            } finally {
                compose.runOnIdle {
                    if (installed) {
                        // Restore selectedModel for an already initialized original, and always restore
                        // exact lazy identity. An uninitialized real provider remains uninitialized.
                        if (originalInitialized) originalDelegate!!.value.selectedModel = originalSelection
                        field!!.set(app, originalDelegate)
                    }
                    store.clear()
                    owner.cancel() // also closes AuthManager's child rotation scope
                }
                http.dispatcher.cancelAll()
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
                assertEquals("FIXTURE: unexpected HTTP attempt (blocked before network)", 0, requests.get())
                originalDelegate?.let { assertEquals("FIXTURE: real provider initialization changed", originalInitialized, it.isInitialized()) }
            }
        }
    }

    private fun report(case: String, nodes: List<SemanticsNode>) {
        val entries = JSONArray()
        nodes.forEach { entries.put(JSONObject().put("id", it.id).put("names", JSONArray(names(it)))
            .put("role", it.config.getOrNull(SemanticsProperties.Role).toString())
            .put("selected", it.config.getOrNull(SemanticsProperties.Selected))
            .put("click", it.config.getOrNull(SemanticsActions.OnClick)?.action != null)) }
        val line = JSONObject().put("case", case).put("synthetic_display_only", true).put("radios", entries).toString()
        Log.i("VOCI_R3A_ACCOUNT", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("r3a_account", line) })
    }
}
