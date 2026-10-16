package de.voci.navigation

import de.voci.audio.PlaybackState

data class SessionCompletion(val sessionId: String, val recall: Boolean)

/** Main-thread claim shared by root and player; storage is the ViewModel's SavedStateHandle. */
class CompletionDelivery(
    private val delivered: () -> List<String>,
    private val save: (List<String>) -> Unit,
) {
    fun claim(state: PlaybackState, expectedId: String?): SessionCompletion? {
        val session = state.session ?: return null
        if (!state.ended || session.id != expectedId ||
            (state.requestId != null && state.requestId != session.id) || session.id in delivered()) return null
        save(delivered() + session.id)
        return SessionCompletion(session.id, session.recall)
    }
}
