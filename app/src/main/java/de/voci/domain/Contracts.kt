package de.voci.domain

import kotlinx.serialization.Serializable

@Serializable data class SessionSpec(val topic:String, val language:String="it-IT", val minutes:Int=1, val recall:Boolean=false, val entryCount:Int?=null) {
    init { require(topic.isNotBlank() && topic.length<=240); require(minutes in listOf(1,3,5)); require(language in listOf("it-IT","es-ES","en-GB","fr-FR","de-DE"));require(entryCount==null || entryCount in listOf(5,10,20)) }
}
@Serializable data class Category(val id:String, val name:String, val custom:Boolean=false)
@Serializable data class VocabItem(val id:String, val target:String, val translation:String, val example:String, val exampleTranslation:String)
@Serializable data class DialogueTurn(val id:String,val speaker:Int,val text:String,val translation:String)
@Serializable data class RecognitionQuestion(val id:String,val itemId:String,val prompt:String,val options:List<String>,val answer:Int)
@Serializable data class LessonContent(val id:String,val title:String,val language:String,val items:List<VocabItem>,val dialogue:List<DialogueTurn> = emptyList(),val questions:List<RecognitionQuestion> = emptyList(),val example:Boolean=false)
@Serializable data class AudioCue(val key:String,val itemId:String,val text:String,val translation:String,val locale:String,val voice:String="",val phase:String="target",val pauseMs:Long=650,val rate:Float=1f,val durationMs:Long=0)
@Serializable data class SessionProgress(val sessionId:String,val lessonId:String,val index:Int=0,val positionMs:Long=0,val playedMs:Long=0,val answers:Map<String,Boolean> = emptyMap(),val uncertain:Set<String> = emptySet(),val completed:Boolean=false,val mediaItemIndex:Int?=null)
enum class ProviderState { SIGNED_OUT, SIGNING_IN, IDENTITY_VERIFIED, PLAN_AUTHORIZED, READY, LIMIT, DISCONNECTED }

object LessonValidator {
    fun validate(lesson:LessonContent):LessonContent {
        require(lesson.id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(lesson.title.length in 1..100 && lesson.language in listOf("it-IT","es-ES","en-GB","fr-FR","de-DE"))
        require(lesson.items.size in 1..20 && lesson.items.map { it.id }.distinct().size==lesson.items.size)
        fun text(s:String) { require(s.isNotBlank() && s.length<=500 && s.none { it.code<32 && it!='\n' }) }
        text(lesson.title)
        for(v in lesson.items) { require(v.id.matches(Regex("[A-Za-z0-9_-]{1,80}")));listOf(v.target,v.translation,v.example,v.exampleTranslation).forEach(::text) }
        require(lesson.items.map { it.target.trim().lowercase(java.util.Locale.ROOT) }.distinct().size==lesson.items.size)
        require(lesson.dialogue.map { it.id }.distinct().size==lesson.dialogue.size)
        require(lesson.dialogue.size<=8 && lesson.questions.size<=6)
        for(d in lesson.dialogue) { require(d.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && d.speaker in 0..1);text(d.text);text(d.translation) }
        require(lesson.questions.map { it.id }.distinct().size==lesson.questions.size)
        for(q in lesson.questions) { require(q.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && lesson.items.any { it.id==q.itemId } && q.options.size in 2..4 && q.answer in q.options.indices);text(q.prompt);q.options.forEach(::text) }
        return lesson
    }
}
object Planner {
    fun fit(cues:List<AudioCue>,budgetMs:Long,speed:Float=1f):List<AudioCue> {
        require(budgetMs in 1000..300000)
        return remaining(cues,cues,budgetMs,speed)
    }
    /** Only whole measured segments. Remove optional repeat cues before essential material. */
    fun remaining(pending:List<AudioCue>,source:List<AudioCue>,budgetMs:Long,speed:Float):List<AudioCue> {
        require(budgetMs in 0..300000 && speed in .75f..1.25f)
        require((pending+source).all { it.durationMs>0 && it.pauseMs in 0..5000 })
        fun duration(c:AudioCue)=kotlin.math.ceil((c.durationMs+c.pauseMs)/speed.toDouble()).toLong()
        val primary=pending.toMutableList()
        var total=primary.sumOf(::duration)
        // A repeated source occurrence is optional too, even when its phase is an introduction.
        val seen=mutableSetOf<String>()
        val optional=primary.indices.filter { val cue=primary[it];val identity="${cue.key}:${cue.phase}:${cue.itemId}";val duplicate=!seen.add(identity);cue.phase=="repeat" || duplicate }.toSet()
        for(i in primary.indices.reversed())if(total>budgetMs && i in optional) { total-=duration(primary[i]);primary.removeAt(i) }
        val result=mutableListOf<AudioCue>();var elapsed=0L
        for(cue in primary)if(elapsed+duration(cue)<=budgetMs) { result+=cue;elapsed+=duration(cue) }
        val repeats=source.ifEmpty { pending }
        while(result.size<500 && elapsed<budgetMs*.98) {
            var added=false
            for(cue in repeats)if(elapsed+duration(cue)<=budgetMs && result.size<500) { result+=cue;elapsed+=duration(cue);added=true }
            if(!added)break
        }
        return result
    }
}
