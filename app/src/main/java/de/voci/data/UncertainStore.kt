package de.voci.data

import de.voci.domain.LessonContent
import de.voci.domain.SessionProgress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Shared serialization covers ViewModels and the coordinator, including legacy migration. */
class UncertainStore(private val preferences:PreferencesStore,private val dao:VociDao) {
 companion object { private val mutations=Mutex() }

 private suspend fun lesson(id:String):LessonContent?=
  dao.lesson(id)?.let { Json.decodeFromString<LessonContent>(it.json) }

 private suspend fun legacy(id:String,live:Set<String>):Set<String> {
  if(preferences.uncertainMarks(id)!=null)return emptySet()
  return live+dao.progressEntries().flatMap { entry->
   val progress=runCatching { Json.decodeFromString<SessionProgress>(entry.json) }.getOrNull()
   if(progress?.lessonId==id)progress.uncertain else emptySet()
  }
 }

 /** Publish while still serialized so an older read cannot overwrite a newer toggle. */
 suspend fun load(id:String,live:Set<String> = emptySet(),publish:(Set<String>)->Unit = {}):Set<String> = mutations.withLock {
  val lesson=lesson(id)
  val valid=lesson?.items?.map { it.id }?.toSet().orEmpty()
  val marks=preferences.editUncertain(id,if(lesson!=null)legacy(id,live) else emptySet()) { it.intersect(valid) }
  val current=if(dao.lesson(id)!=null)marks else preferences.editUncertain(id,emptySet()) { emptySet() }
  publish(current)
  current
 }

 suspend fun change(id:String,itemId:String,addOnly:Boolean,live:()->Set<String>,publish:(Set<String>)->Unit) = mutations.withLock {
  val lesson=lesson(id)?:run {
   preferences.editUncertain(id,emptySet()) { emptySet() }
   error("Lesson no longer available")
  }
  val valid=lesson.items.map { it.id }.toSet()
  require(itemId in valid) { "Item no longer available" }
  val marks=preferences.editUncertain(id,legacy(id,live())) { stored->
   val current=stored.intersect(valid)
   if(addOnly || itemId !in current)current+itemId else current-itemId
  }
  // A deletion may run while DataStore suspends. Never publish its stale marks.
  if(dao.lesson(id)==null) {
   preferences.editUncertain(id,emptySet()) { emptySet() }
   error("Lesson no longer available")
  }
  publish(marks)
 }

 suspend fun clear(id:String)=mutations.withLock {
  // Retain the empty tombstone: old session progress must never resurrect removals.
  preferences.editUncertain(id,emptySet()) { emptySet() }
 }
}
