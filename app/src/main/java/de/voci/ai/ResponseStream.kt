package de.voci.ai
import kotlinx.serialization.json.*

class ProviderFailure(val kind:String,val httpStatus:Int?=null,val requestId:String?=null,val param:String?=null,val bodyShape:String?=null):Exception(kind)
object ProviderErrors {
 fun http(status:Int,body:String,requestId:String?):ProviderFailure {
  val obj=runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
  val error=obj?.get("error") as? JsonObject
  val code=(error?.get("code") as? JsonPrimitive)?.contentOrNull
  val param=(error?.get("param") as? JsonPrimitive)?.contentOrNull
  return ProviderFailure(code?.take(128)?:if(status==429)"limit" else "http_$status",status,requestId?.take(128),param?.take(128),obj?.keys?.sorted()?.joinToString(",")?.take(128)?:"non-json")
 }
}
object ResponseStream {
 fun read(lines:Sequence<String>):String {
  val out=StringBuilder();val event=StringBuilder();var completed=false
  fun consume() {
   if(event.isEmpty())return
   val raw=event.toString();event.clear()
   if(raw=="[DONE]")return
   val o=try { Json.parseToJsonElement(raw).jsonObject } catch(_:Exception) { throw ProviderFailure("invalid_sse") }
   when(o["type"]?.jsonPrimitive?.content) {
    "response.output_text.delta"->{ if(completed)throw ProviderFailure("after_terminal");out.append(o["delta"]?.jsonPrimitive?.content.orEmpty());if(out.length>64000)throw ProviderFailure("oversized") }
    "response.failed","error"->throw ProviderFailure(o["response"]?.jsonObject?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content?:o["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content?:o["code"]?.jsonPrimitive?.content?:"failed")
    "response.incomplete"->throw ProviderFailure("incomplete")
    "response.completed"->{ require(o["response"]?.jsonObject?.get("status")?.jsonPrimitive?.content=="completed");completed=true }
   }
  }
  for(line in lines) {
   if(line.length>131072)throw ProviderFailure("oversized")
   if(line.isEmpty())consume() else if(line.startsWith("data:")) { if(event.isNotEmpty())event.append('\n');event.append(line.substring(5).trimStart());if(event.length>131072)throw ProviderFailure("oversized") }
  }
  consume()
  if(!completed || out.isBlank())throw ProviderFailure("interrupted")
  return out.toString()
 }
}
