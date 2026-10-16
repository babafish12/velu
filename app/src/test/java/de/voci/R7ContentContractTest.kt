package de.voci

import de.voci.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Pure content contract; no provider, transport, login or audio claim. Existing APIs only.
 * entryCount request/persistence/exact parser mismatch tests follow once that API exists.
 */
class R7ContentContractTest {
    @Test fun acceptsTwentyDistinctEntriesWithoutTruncation() {
        val expected = lesson(20)
        val decoded = Json.decodeFromString<LessonContent>(Json.encodeToString(expected))
        val validated = try { LessonValidator.validate(decoded) }
        catch (e: IllegalArgumentException) {
            throw AssertionError("R7 CONTENT PRODUCT: validator must accept 20 distinct entries", e)
        }
        assertEquals("R7 CONTENT PRODUCT: preserve all 20 entries and stable IDs", expected, validated)
    }

    @Test fun rejectsOversizedDuplicateAndInvalidContent_negativeControls() {
        // These malformed controls use an accepted small size so max14 cannot mask their defects.
        val valid = lesson(5)
        assertEquals("FIXTURE: valid small content", valid, LessonValidator.validate(valid))
        val first = valid.items.first()
        val invalid = listOf(
            "over 20 entries" to lesson(21),
            "empty content" to valid.copy(items = emptyList()),
            "duplicate IDs" to valid.copy(items = valid.items.dropLast(1) + first.copy(target = "Other water")),
            "duplicate normalized target" to valid.copy(items = valid.items.dropLast(1) + first.copy(id = "unique", target = "  WATER 1  ")),
            "oversized text" to valid.copy(items = listOf(first.copy(target = "x".repeat(501)))),
            "control character" to valid.copy(items = listOf(first.copy(target = "Water\u0000"))),
            "unknown language" to valid.copy(language = "xx-XX"),
            "question outside lesson" to valid.copy(questions = listOf(valid.questions.first().copy(itemId = "absent"))),
            "invalid answer" to valid.copy(questions = listOf(valid.questions.first().copy(answer = 9)))
        )
        for ((reason, content) in invalid) {
            val decoded = Json.decodeFromString<LessonContent>(Json.encodeToString(content))
            val rejected = try { LessonValidator.validate(decoded); false } catch (_: IllegalArgumentException) { true }
            assertTrue("R7 CONTENT PRODUCT: reject $reason", rejected)
        }
    }

    private fun lesson(count: Int) = LessonContent("r7-content", "Water at the restaurant", "en-GB",
        (1..count).map { n -> VocabItem("water-$n", "Water $n", "Wasser $n",
            "Please bring water $n.", "Bitte bringen Sie Wasser $n.") },
        dialogue = listOf(DialogueTurn("d1", 0, "Water 1, please.", "Wasser 1, bitte."),
            DialogueTurn("d2", 1, "Here is water 1.", "Hier ist Wasser 1.")),
        questions = listOf(RecognitionQuestion("q1", "water-1", "Which water?", listOf("Wasser 1", "Wasser 2"), 0),
            RecognitionQuestion("q2", "water-2", "Which other water?", listOf("Wasser 2", "Wasser 1"), 0)))
}
