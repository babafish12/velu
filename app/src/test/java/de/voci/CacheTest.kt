package de.voci
import de.voci.audio.AudioFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class CacheTest {
 @Test fun everyParameterSeparatesCache() {
  fun k(t:String="text",l:String="it",v:String="a",e:String="tts",r:Float=1f)=AudioFiles.key(t,l,v,e,r)
  assertEquals(6,setOf(k(),k(t="text2"),k(l="en"),k(v="b"),k(e="other"),k(r=.8f)).size)
 }
 @Test fun missingFileNotOffline() { assertFalse(AudioFiles.valid(File("missing.wav"),100,"hash")) }
 @Test fun changedFileNotOffline() { val f=File.createTempFile("voci","wav");try { f.writeBytes(ByteArray(100){1});val hash=AudioFiles.hash(f);assertTrue(AudioFiles.valid(f,100,hash));f.writeBytes(ByteArray(100){2});assertFalse(AudioFiles.valid(f,100,hash)) }finally { f.delete() } }
}
