package de.voci.auth

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

class OAuthAttempt(val state:String,val nonce:String,val verifier:String,val redirect:String,val expiresAt:Long,val client:String?=null) {
 @Volatile private var used=false
 val consumed:Boolean get()=used
 @Synchronized fun consume(url:String,now:Long):Pair<String,String> {
  require(!used) { "Anmeldung bereits beendet" }
  if(now>=expiresAt) { used=true;throw IllegalArgumentException("Anmeldung abgelaufen") }
  val uri=try { URI(url) } catch(_:Exception) { throw IllegalArgumentException("Ungültiger Callback") }
  val expected=URI(redirect)
  require(uri.scheme=="http" && uri.host=="127.0.0.1" && uri.port==expected.port && uri.rawPath=="/auth/callback" && uri.userInfo==null && uri.fragment==null)
  val entries=uri.rawQuery.orEmpty().split('&').map { val kv=it.split('=',limit=2);require(kv.size==2);URLDecoder.decode(kv[0],"UTF-8") to URLDecoder.decode(kv[1],"UTF-8") }
  require(entries.map { it.first }.distinct().size==entries.size)
  val q=entries.toMap()
  require(MessageDigest.isEqual(q["state"].orEmpty().toByteArray(),state.toByteArray()))
  val issued=q["client_id"]?:client
  if(issued!=null) {
   require(issued.matches(Regex("oaiapp_[A-Za-z0-9_-]{1,249}")))
   require(client==null || client==issued)
  }
  if("error" in q) {
   require(q["error"]=="access_denied")
   used=true
   throw IllegalArgumentException("Anmeldung abgebrochen")
  }
  val code=q["code"].orEmpty();require(code.isNotBlank() && code.length<=4096)
  require(issued!=null)
  // Invalid requests above leave the original browser attempt pending.
  used=true
  return code to issued
 }
}
