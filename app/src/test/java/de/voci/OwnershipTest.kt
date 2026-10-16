package de.voci
import de.voci.auth.SessionEpoch
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class OwnershipTest {
 @Test fun logoutInvalidatesInFlightRefresh()=runBlocking {
  val fence=SessionEpoch();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0
  val work=launch { val snapshot=fence.current();entered.complete(Unit);release.await();if(fence.valid(snapshot))writes++ }
  entered.await();fence.invalidate();release.complete(Unit);work.join();assertEquals(0,writes)
 }
 @Test fun newerLoginCannotBeOverwritten() { val fence=SessionEpoch();val old=fence.current();fence.invalidate();val new=fence.current();assertFalse(fence.valid(old));assertTrue(fence.valid(new)) }
 @Test fun refreshMutexDeduplicatesExpiryCheck()=runBlocking {
  val mutex=Mutex();var expired=true;var requests=0
  List(20) { async { mutex.withLock { if(expired) { requests++;delay(1);expired=false } } } }.awaitAll()
  assertEquals(1,requests)
 }
}
