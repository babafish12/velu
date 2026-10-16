package de.voci

import de.voci.audio.PlaybackState
import de.voci.audio.PreparedSession
import de.voci.data.Examples
import de.voci.navigation.CompletionDelivery
import de.voci.navigation.SessionCompletion
import org.junit.Assert.*
import org.junit.Test

class CompletionDeliveryTest {
    private var saved = emptyList<String>()
    private fun gate() = CompletionDelivery({ saved }, { saved = it })
    private fun ended(id: String, recall: Boolean = false) = PlaybackState(
        session = PreparedSession(id, Examples.lessons.first(), emptyList(), recall, 1f), ended = true, requestId = id)

    @Test fun listeningCompletionNeedsNoRecall() {
        assertEquals(SessionCompletion("a", false), gate().claim(ended("a"), "a"))
    }
    @Test fun optionalRecallIsTakenFromCompletedSession() {
        assertEquals(SessionCompletion("a", true), gate().claim(ended("a", true), "a"))
    }
    @Test fun competingRootAndPlayerDeliverOnlyOnce() {
        val root = gate(); val player = gate()
        assertNotNull(root.claim(ended("a"), "a"))
        assertNull(player.claim(ended("a"), "a"))
    }
    @Test fun restorationAndBackDoNotRedeliver() {
        assertNotNull(gate().claim(ended("a"), "a"))
        val restored = gate()
        repeat(3) { assertNull(restored.claim(ended("a"), "a")) }
    }
    @Test fun replayAndLaterSessionsEachDeliverOnceWithoutRevivingOldCompletion() {
        val gate = gate()
        for (id in listOf("a", "b", "c")) {
            assertNull(gate.claim(ended(id).copy(ended = false), id))
            assertNotNull(gate.claim(ended(id), id))
            assertNull(gate.claim(ended(id), id))
        }
        assertNull(gate.claim(ended("a"), "a"))
    }
    @Test fun staleRouteRequestAndMissingSessionCannotConsumeCompletion() {
        val gate = gate()
        assertNull(gate.claim(PlaybackState(ended = true), "a"))
        assertNull(gate.claim(ended("a"), "b"))
        assertNull(gate.claim(ended("a").copy(requestId = "b"), "a"))
        assertNotNull(gate.claim(ended("a"), "a"))
    }
}
