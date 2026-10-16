package de.voci.data
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.settings by preferencesDataStore("settings")
data class Preferences(val language:String="it-IT",val minutes:Int=1,val recall:Boolean=false,val reduced:Boolean=false,val speed:Float=1f,val provider:String="chatgpt",val server:String="",val modelPath:String="",val modelHash:String="",val wheelTranslationFirst:Boolean=false,val entryCount:Int=when(minutes) { 3->10;5->20;else->5 })
class PreferencesStore(private val store:androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) {
 constructor(context:Context):this(context.settings)
 // The complete lesson ID is one key suffix; item IDs are separate set values.
 // An existing empty set means explicitly unmarked, not pending legacy migration.
 private val uncertainPrefix="uncertain.lesson.v1:"
 val uncertainFlow=store.data.map { p->
  p.asMap().filterKeys { it.name.startsWith(uncertainPrefix) }.mapNotNull { (key,value)->
   (value as? Set<*>)?.filterIsInstance<String>()?.toSet()?.let { key.name.removePrefix(uncertainPrefix) to it }
  }.toMap()
 }
 suspend fun uncertainMarks(lessonId:String):Set<String>?=store.data.first()[stringSetPreferencesKey(uncertainPrefix+lessonId)]
 suspend fun editUncertain(lessonId:String,legacy:Set<String>,change:(Set<String>)->Set<String>):Set<String> {
  val key=stringSetPreferencesKey(uncertainPrefix+lessonId)
  val saved=store.edit { p->p[key]=change(p[key]?:legacy) }
  return saved[key].orEmpty()
 }
 // Read legacy values unchanged. Only explicit confirmation changes provider; unknown keys survive edit.
 suspend fun confirmChatGpt() { store.edit { it[stringPreferencesKey("provider")]="chatgpt" } }
 val flow=store.data.map { p->Preferences(p[stringPreferencesKey("language")]?:"it-IT",p[intPreferencesKey("minutes")]?:1,p[booleanPreferencesKey("recall")]?:false,p[booleanPreferencesKey("reduced")]?:false,p[floatPreferencesKey("speed")]?:1f,p[stringPreferencesKey("provider")] ?: if(p.asMap().keys.any { it.name in setOf("server","modelPath","modelHash") }) "" else "chatgpt",p[stringPreferencesKey("server")]?:"",p[stringPreferencesKey("modelPath")]?:"",p[stringPreferencesKey("modelHash")]?:"",p[booleanPreferencesKey("wheelTranslationFirst")]?:false,
  p[intPreferencesKey("entryCount")]?.takeIf { it in listOf(5,10,20) } ?: when(p[intPreferencesKey("minutes")]) { 3->10;5->20;else->5 }) }
 suspend fun toggleWheelLanguage() {
  val key=booleanPreferencesKey("wheelTranslationFirst")
  // DataStore serializes the read/modify/write, including taps before the UI flow catches up.
  store.edit { p->p[key]=!(p[key]?:false) }
 }
 suspend fun string(k:String,v:String) { store.edit { it[stringPreferencesKey(k)]=v } }
 suspend fun bool(k:String,v:Boolean) { store.edit { it[booleanPreferencesKey(k)]=v } }
 suspend fun minutes(v:Int) { require(v in listOf(1,3,5));store.edit { it[intPreferencesKey("minutes")]=v } }
 suspend fun entryCount(v:Int) { require(v in listOf(5,10,20));store.edit { it[intPreferencesKey("entryCount")]=v } }
 suspend fun speed(v:Float) { require(v in .75f..1.25f);store.edit { it[floatPreferencesKey("speed")]=v } }
}
