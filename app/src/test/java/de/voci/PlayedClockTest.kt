package de.voci
import de.voci.audio.PlayedClock
import org.junit.Assert.*
import org.junit.Test
class PlayedClockTest {
 @Test fun legacyProgressWithoutMediaPhaseRetainsSpeechFallback() {
  val p=kotlinx.serialization.json.Json.decodeFromString<de.voci.domain.SessionProgress>("""{"sessionId":"old","lessonId":"lesson","index":3,"positionMs":120,"playedMs":4000}""")
  assertNull(p.mediaItemIndex);assertEquals(6,p.mediaItemIndex?:p.index*2);assertEquals(120,p.positionMs)
 }

 @Test fun countsTransitionTail() { val c=PlayedClock();c.sample(0,0,true,1f);c.sample(0,800,true,1f);c.discontinuity(0,1000,1,0);c.sample(1,500,false,1f);assertEquals(1500,c.totalMs) }
 @Test fun pauseWallTimeDoesNotCount() { val c=PlayedClock();c.sample(0,0,true,1f);c.sample(0,1000,false,1f);repeat(20){c.sample(0,1000,false,1f)};assertEquals(1000,c.totalMs) }
 @Test fun seekJumpDoesNotCount() { val c=PlayedClock();c.sample(0,0,true,1f);c.discontinuity(0,100,2,1000);c.sample(2,1200,false,1f);assertEquals(300,c.totalMs) }
 @Test fun speedChangeUsesPreviousRate() { val c=PlayedClock();c.sample(0,0,true,1f);c.sample(0,1000,true,1.25f);c.sample(0,2250,false,1.25f);assertEquals(2000,c.totalMs) }
}
