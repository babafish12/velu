package de.voci.audio
import java.io.File
import java.security.MessageDigest

object AudioFiles {
 fun key(text:String,locale:String,voice:String,engine:String,rate:Float):String=MessageDigest.getInstance("SHA-256").digest(listOf(text,locale,voice,engine,rate.toString()).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
 fun valid(file:File,bytes:Long,sha:String):Boolean=file.isFile && bytes>44 && file.length()==bytes && runCatching { hash(file)==sha }.getOrDefault(false)
 fun hash(file:File):String { val md=MessageDigest.getInstance("SHA-256");file.inputStream().use { stream-> val buffer=ByteArray(65536);var n=stream.read(buffer);while(n>=0) { md.update(buffer,0,n);n=stream.read(buffer) } };return md.digest().joinToString("") { "%02x".format(it) } }
}
