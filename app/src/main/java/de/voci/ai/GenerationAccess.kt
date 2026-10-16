package de.voci.ai

import de.voci.domain.LessonContent
import de.voci.domain.SessionSpec

/** Central boundary shared by the Sprint production path and regression fixtures. */
object GenerationAccess {
 fun requireChatGpt(provider:String) {
  if(provider!="chatgpt")throw ProviderFailure("provider_confirmation")
 }
 suspend fun generate(provider:String,chatGpt:LessonProvider,spec:SessionSpec):LessonContent {
  requireChatGpt(provider)
  return chatGpt.generate(spec)
 }
}
