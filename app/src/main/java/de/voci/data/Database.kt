package de.voci.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class LessonEntity(@PrimaryKey val id:String,val title:String,val language:String,val json:String,val categoryId:String?,val saved:Boolean=false,val measuredMs:Long=0)
@Entity data class CategoryEntity(@PrimaryKey val id:String,val name:String,val custom:Boolean=false)
@Entity data class AudioEntity(@PrimaryKey val key:String,val path:String,val durationMs:Long,val bytes:Long,val sha256:String,val pinned:Boolean=false)
@Entity data class ProgressEntity(@PrimaryKey val sessionId:String,val json:String)
@Dao interface VociDao {
 @Query("SELECT * FROM LessonEntity ORDER BY title") fun lessons():Flow<List<LessonEntity>>
 @Query("SELECT * FROM LessonEntity WHERE id=:id") suspend fun lesson(id:String):LessonEntity?
 @Upsert suspend fun putLesson(lesson:LessonEntity)
 @Query("DELETE FROM LessonEntity WHERE id=:id") suspend fun deleteLesson(id:String)
 @Query("SELECT * FROM CategoryEntity ORDER BY name") fun categories():Flow<List<CategoryEntity>>
 @Upsert suspend fun putCategory(category:CategoryEntity)
 @Query("UPDATE CategoryEntity SET name=:name WHERE id=:id") suspend fun renameCategory(id:String,name:String)
 @Query("UPDATE LessonEntity SET categoryId=NULL WHERE categoryId=:id") suspend fun unlinkCategory(id:String)
 @Query("DELETE FROM CategoryEntity WHERE id=:id AND custom=1") suspend fun removeCategory(id:String)
 @Transaction suspend fun deleteCategory(id:String) { unlinkCategory(id);removeCategory(id) }
 @Query("SELECT * FROM AudioEntity WHERE `key`=:key") suspend fun audio(key:String):AudioEntity?
 @Query("SELECT * FROM AudioEntity") suspend fun audioFiles():List<AudioEntity>
 @Upsert suspend fun putAudio(audio:AudioEntity)
 @Query("DELETE FROM AudioEntity WHERE `key`=:key") suspend fun removeAudio(key:String)
 @Upsert suspend fun putProgress(progress:ProgressEntity)
 @Query("SELECT * FROM ProgressEntity") suspend fun progressEntries():List<ProgressEntity>
 @Query("DELETE FROM ProgressEntity WHERE sessionId=:id") suspend fun removeProgress(id:String)
 @Query("SELECT * FROM ProgressEntity ORDER BY rowid DESC LIMIT 1") suspend fun latestProgress():ProgressEntity?
}
@Database(entities=[LessonEntity::class,CategoryEntity::class,AudioEntity::class,ProgressEntity::class],version=1,exportSchema=true)
abstract class VociDatabase:RoomDatabase() { abstract fun dao():VociDao }
