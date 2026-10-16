package de.voci.data
import de.voci.domain.*
import kotlinx.serialization.json.Json
import java.util.UUID

class Repository(val dao:VociDao) {
 val lessons=dao.lessons()
 val categories=dao.categories()
 suspend fun seed() {
  for((id,name) in Examples.categories) dao.putCategory(CategoryEntity(id,name))
  for(l in Examples.lessons) if(dao.lesson(l.id)==null) save(l,Examples.category(l))
 }
 suspend fun save(l:LessonContent,category:String?=null,saved:Boolean=false,ms:Long=0) { LessonValidator.validate(l);dao.putLesson(LessonEntity(l.id,l.title,l.language,Json.encodeToString(l),category,saved,ms)) }
 suspend fun content(id:String)=dao.lesson(id)?.let { Json.decodeFromString<LessonContent>(it.json) }
 suspend fun newCategory(name:String) { require(name.trim().length in 1..60);dao.putCategory(CategoryEntity(UUID.randomUUID().toString(),name.trim(),true)) }
 suspend fun rename(id:String,name:String) { require(name.trim().length in 1..60);dao.renameCategory(id,name.trim()) }
}
