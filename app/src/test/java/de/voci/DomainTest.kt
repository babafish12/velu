package de.voci
import de.voci.domain.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val item=VocabItem("a","Ciao","Hallo","Ciao, Anna.","Hallo, Anna.")
    private fun lesson(items:List<VocabItem> = listOf(item))=LessonContent("l","Restaurant","it-IT",items)
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateExpressionsWithDifferentIds() { LessonValidator.validate(lesson(listOf(item,item.copy(id="b")))) }
    @Test fun acceptsValid() { assertEquals(lesson(),LessonValidator.validate(lesson())) }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateIds() { LessonValidator.validate(lesson(listOf(item,item))) }
    @Test(expected=IllegalArgumentException::class) fun rejectsEmpty() { LessonValidator.validate(lesson(emptyList())) }
    @Test(expected=IllegalArgumentException::class) fun rejectsOversizedText() { LessonValidator.validate(lesson(listOf(item.copy(target="a".repeat(501))))) }
    @Test(expected=IllegalArgumentException::class) fun rejectsQuestionOutsideContent() { LessonValidator.validate(lesson().copy(questions=listOf(RecognitionQuestion("q","missing","?",listOf("a","b"),0)))) }
    @Test fun measuredBudget() { val cues=(1..20).map { AudioCue("$it","a","Ciao","Hallo","it-IT",durationMs=3000,pauseMs=1000) }; for (b in listOf(60000L,180000L,300000L)) { val p=Planner.fit(cues,b); val d=p.sumOf { it.durationMs+it.pauseMs }; assertTrue("$d for $b",d<=b && d>=b*.9) } }
    @Test fun doesNotCutLongSegment() { assertTrue(Planner.fit(listOf(AudioCue("a","a","a","a","it",durationMs=70000)),60000).isEmpty()) }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnmeasured() { Planner.fit(listOf(AudioCue("a","a","a","a","it")),60000) }
    @Test fun unansweredIsNotMastered() { assertTrue(SessionProgress("s","l").answers.isEmpty()) }
}
