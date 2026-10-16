package de.voci.navigation
import kotlinx.serialization.Serializable
@Serializable data object Sprint
@Serializable data object Categories
@Serializable data class CategoryDetail(val categoryId:String)
@Serializable data object Library
@Serializable data class LessonDetail(val lessonId:String)
@Serializable data class PlayerRoute(val sessionId:String)
@Serializable data class Recall(val sessionId:String)
@Serializable data class Result(val sessionId:String)
@Serializable data object Settings
@Serializable data object Account
@Serializable data object Audio
@Serializable data object LocalAi
@Serializable data object Storage
@Serializable data object Privacy
@Serializable data object Licenses
@Serializable data class LicenseText(val licenseId:String)
@Serializable data object LicenseNotices
// Infrastructure root of the independent session controller, never a product page.
@Serializable data object SessionIdle
