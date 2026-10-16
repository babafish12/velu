package de.voci.auth

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.Closeable
import java.io.IOException

/** One short-lived loopback listener. Unrelated requests cannot consume its browser attempt. */
class Loopback:Closeable {
 private val server=ServerSocket(0,2,InetAddress.getByName("127.0.0.1")).apply { soTimeout=1000 }
 @Volatile private var peer:Socket?=null
 val redirect="http://127.0.0.1:${server.localPort}/auth/callback"
 fun await(attempt:OAuthAttempt):Pair<String,String> {
  val deadline=minOf(attempt.expiresAt,System.currentTimeMillis()+300_000)
  while(System.currentTimeMillis()<deadline && !server.isClosed) {
   server.soTimeout=minOf(1000L,(deadline-System.currentTimeMillis()).coerceAtLeast(1)).toInt()
   val socket=try { server.accept() } catch(_:SocketTimeoutException) { continue }
   peer=socket
   if(server.isClosed) { socket.close();break }
   socket.use {
    try {
     it.soTimeout=minOf(2000L,(deadline-System.currentTimeMillis()).coerceAtLeast(1)).toInt()
     val header=StringBuilder();val input=it.getInputStream()
     val headerDeadline=minOf(deadline,System.currentTimeMillis()+2000)
     while(header.length<8192 && !header.endsWith("\r\n\r\n")) {
      val remaining=headerDeadline-System.currentTimeMillis();if(remaining<=0)throw SocketTimeoutException()
      it.soTimeout=remaining.coerceAtMost(2000).toInt();val c=input.read();if(c<0)break;header.append(c.toChar())
     }
     val lines=header.toString().split("\r\n");val request=lines.first().split(" ")
     val host=lines.drop(1).filter { line->line.startsWith("Host:",true) }.singleOrNull()?.substringAfter(":")?.trim()
     if(!header.endsWith("\r\n\r\n") || header.length>=8192 || request.size!=3 || request[0]!="GET" || request[2] !in setOf("HTTP/1.0","HTTP/1.1") || !request[1].startsWith("/auth/callback?") || host!="127.0.0.1:${server.localPort}") {
      respond(it,false);continue
     }
     val result=runCatching { attempt.consume("http://$host${request[1]}",System.currentTimeMillis()) }
     respond(it,result.isSuccess || attempt.consumed)
     if(result.isSuccess)return result.getOrThrow()
     if(attempt.consumed)throw result.exceptionOrNull()!!
    } catch(_:IOException) {
     // A closed/idle peer affects only its own connection, never the pending login.
    } finally { if(peer===it)peer=null }
   }
  }
  throw IllegalArgumentException("Anmeldung abgelaufen")
 }
 private fun respond(socket:Socket,accepted:Boolean) {
  val body=if(accepted)"Velu: Bitte zur App zurueckkehren." else "Ungueltige Anfrage."
  val status=if(accepted)"200 OK" else "400 Bad Request"
  try { socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()) } catch(_:IOException) { /* A valid consumed code remains usable if its browser closes. */ }
 }
 override fun close() { try { server.close() } finally { peer?.close() } }
}
