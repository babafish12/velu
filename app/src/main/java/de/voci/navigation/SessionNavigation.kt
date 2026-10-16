package de.voci.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.voci.VociViewModel
import de.voci.audio.PlaybackState
import kotlinx.coroutines.flow.first

/** Two ownership domains. Tab entries never pass through a session cleanup operation.
 * IDs below reference actual Navigation entries; they are never assigned to an entry or used
 * to manufacture state. Navigation itself saves both controllers and their entry owners.
 */
class VociNavigator internal constructor(
    val tabs: NavHostController,
    val sessions: NavHostController,
    private val playback: () -> PlaybackState,
    restored: List<String> = emptyList(),
) {
    var sessionVisible by mutableStateOf(restored.getOrNull(0) == "true")
        private set
    var originEntryId by mutableStateOf(restored.getOrNull(1).orEmpty())
        private set
    var originBranch by mutableStateOf(restored.getOrNull(2).orEmpty())
        private set
    private var branch by mutableStateOf(restored.getOrNull(3) ?: "sprint")
    private var returnAudioId by mutableStateOf(restored.getOrNull(4).orEmpty())
    private var returnPlayerId by mutableStateOf(restored.getOrNull(5).orEmpty())
    private var playbackRestorationFinished = false
    private var observedRequest = false

    val currentBackStackEntry: NavBackStackEntry?
        get() = if (sessionVisible) sessions.currentBackStackEntry else tabs.currentBackStackEntry
    val currentDestination get() = currentBackStackEntry?.destination
    val canGoBack get() = sessionVisible || tabs.previousBackStackEntry != null

    internal fun save() = listOf(sessionVisible.toString(), originEntryId, originBranch,
        branch, returnAudioId, returnPlayerId)

    /** Scaffold installs its subcomposed hosts after root effects can start. Await real
     * entries rather than dropping reconciliation or consuming a completion too early.
     */
    internal suspend fun awaitReady() {
        tabs.currentBackStackEntryFlow.first()
        sessions.currentBackStackEntryFlow.first()
    }

    private fun forgetReturn() { returnAudioId = ""; returnPlayerId = "" }

    /** No session saveState/restoreState aliases, including equal Player/Recall/Result routes. */
    private fun disposeSession() {
        sessionVisible = false
        forgetReturn()
        sessions.popBackStack(SessionIdle, inclusive = false)
        originEntryId = ""
        originBranch = ""
    }

    fun tab(route: Any) {
        disposeSession()
        tabs.navigate(route) {
            popUpTo(tabs.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        branch = when (route) {
            Sprint -> "sprint"
            Categories -> "categories"
            Library -> "library"
            Settings -> "settings"
            else -> error("Not a tab")
        }
    }

    /** Ordinary details are always owned by the retained tab controller. */
    fun navigate(route: Any) {
        require(route !is PlayerRoute && route !is Recall && route !is Result)
        tabs.navigate(route)
    }

    fun openPlayer(id: String) {
        val actual = playback()
        if (actual.ended || (actual.requestId ?: actual.session?.id) != id) return
        present(PlayerRoute(id))
    }

    fun complete(id: String, recall: Boolean) {
        val actual = playback()
        if (!actual.ended || actual.session?.id != id ||
            (actual.requestId != null && actual.requestId != id)) return
        present(if (recall) Recall(id) else Result(id))
    }

    fun showResult(id: String) = complete(id, recall = false)

    private fun present(route: Any) {
        val current = sessions.currentBackStackEntry
        val same = when (route) {
            is PlayerRoute -> current?.destination?.hasRoute<PlayerRoute>() == true && current.toRoute<PlayerRoute>() == route
            is Recall -> current?.destination?.hasRoute<Recall>() == true && current.toRoute<Recall>() == route
            is Result -> current?.destination?.hasRoute<Result>() == true && current.toRoute<Result>() == route
            else -> false
        }
        // Preserve the origin only while replacing a visible session. Completion while away
        // belongs above the currently visible retained branch (including its Audio entry).
        if (!sessionVisible) {
            originEntryId = tabs.currentBackStackEntry?.id.orEmpty()
            originBranch = branch
        }
        forgetReturn()
        if (!same) {
            sessions.popBackStack(SessionIdle, inclusive = false)
            sessions.navigate(route)
        }
        sessionVisible = true
    }

    fun audio() {
        val player = sessions.currentBackStackEntry ?: return
        if (!sessionVisible || !player.destination.hasRoute<PlayerRoute>()) return
        val id = player.toRoute<PlayerRoute>().sessionId
        val actual = playback()
        if (actual.ended || (actual.requestId ?: actual.session?.id) != id) return
        // The actual origin is still underneath the session; no tab pop/save/restore occurs.
        check(tabs.currentBackStackEntry?.id == originEntryId && branch == originBranch)
        tabs.navigate(Audio)
        returnAudioId = requireNotNull(tabs.currentBackStackEntry).id
        returnPlayerId = player.id
        sessionVisible = false
    }

    /** Also used by the root predictive-back commit; cancellation does not mutate either host. */
    fun popBackStack(): Boolean {
        if (sessionVisible) {
            disposeSession()
            return true
        }
        val audio = tabs.currentBackStackEntry
        val player = sessions.currentBackStackEntry
        val actual = playback()
        val canReturn = audio?.id == returnAudioId && player?.id == returnPlayerId &&
            player?.destination?.hasRoute<PlayerRoute>() == true && !actual.ended &&
            player.toRoute<PlayerRoute>().sessionId == (actual.requestId ?: actual.session?.id) &&
            tabs.previousBackStackEntry?.id == originEntryId && branch == originBranch
        val popped = tabs.popBackStack()
        if (canReturn && popped) {
            forgetReturn()
            sessionVisible = true
        } else if (returnPlayerId.isNotEmpty()) {
            disposeSession()
        }
        return popped
    }

    /** Called after the existing idempotent playback restore completes. An initial empty
     * snapshot is not a cancellation; a settled empty restore or cancelled request is.
     */
    fun playbackRestored() {
        playbackRestorationFinished = true
        playbackChanged()
    }

    /** Supersession is independent of whether the Player or Audio is currently visible. */
    fun playbackChanged() {
        val actual = playback()
        val id = actual.requestId ?: actual.session?.id
        if (id == null) {
            if (playbackRestorationFinished || observedRequest) disposeSession()
            return
        }
        observedRequest = true
        val entry = sessions.currentBackStackEntry ?: return
        val owned = when {
            entry.destination.hasRoute<PlayerRoute>() -> entry.toRoute<PlayerRoute>().sessionId
            entry.destination.hasRoute<Recall>() -> entry.toRoute<Recall>().sessionId
            entry.destination.hasRoute<Result>() -> entry.toRoute<Result>().sessionId
            else -> return
        }
        if (owned != id) disposeSession()
    }
}

@Composable
fun rememberVociNavigator(vm: VociViewModel): VociNavigator {
    val tabs = rememberNavController()
    val sessions = rememberNavController()
    return rememberSaveable(tabs, sessions, saver = listSaver<VociNavigator, String>(
        save = { it.save() },
        restore = { VociNavigator(tabs, sessions, { vm.app.playback.state.value }, it) },
    )) { VociNavigator(tabs, sessions, { vm.app.playback.state.value }) }
}
