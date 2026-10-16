import de.voci.auth.*;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.*;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent real AuthManager regression. Only scheduling, vault and HTTP boundaries are injected. */
public class AuthRecoveryRotationRegression {
    private static final class QueuedDispatcher extends CoroutineDispatcher {
        final LinkedBlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicInteger executions = new AtomicInteger();
        @Override public void dispatch(CoroutineContext context, Runnable runnable) {
            dispatches.incrementAndGet();
            pending.add(runnable);
        }
        void step() throws Exception {
            Runnable next = pending.poll(5, TimeUnit.SECONDS);
            assertNotNull("Expected a controlled owner-context continuation", next);
            executions.incrementAndGet();
            next.run();
        }
    }
    /** Only the first two dispatches are recovery reads; all later work uses real IO. */
    private static final class RecoveryIoDispatcher extends CoroutineDispatcher {
        final LinkedBlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicInteger controlledDispatches = new AtomicInteger();
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger delegatedDispatches = new AtomicInteger();
        @Override public void dispatch(CoroutineContext context, Runnable runnable) {
            if (dispatches.incrementAndGet() <= 2) {
                controlledDispatches.incrementAndGet();
                pending.add(runnable);
            } else {
                delegatedDispatches.incrementAndGet();
                Dispatchers.getIO().dispatch(context, runnable);
            }
        }
        void step() {
            Runnable next = pending.poll();
            assertNotNull("Expected a controlled recovery-IO runnable", next);
            executions.incrementAndGet();
            next.run();
        }
    }
    /** Legal synchronous withContext completion: no owner resumption need be queued. */
    private static final class InlineIoDispatcher extends CoroutineDispatcher {
        @Override public boolean isDispatchNeeded(CoroutineContext context) { return false; }
        @Override public void dispatch(CoroutineContext context, Runnable runnable) {
            fail("Inline IO must never dispatch a runnable");
        }
    }
    private static final class RecoveringStore implements CredentialStore {
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch retryReadsFinished = new CountDownLatch(2);
        volatile VaultState data;
        RecoveringStore() {
            Registration expired = new Registration("oaiapp_A", "A", "A", "synthetic-old-access",
                "synthetic-old-refresh", "", Set.of("resource.invoke", "chatgpt.tokens.use.direct"), 1L);
            data = new VaultState("urn:uuid:00000000-0000-4000-8000-000000000001", List.of(expired), "oaiapp_A");
        }
        @Override public synchronized VaultState read() {
            if (reads.incrementAndGet() == 1) throw new IllegalStateException("synthetic transient vault read failure");
            VaultState result = data;
            retryReadsFinished.countDown();
            return result;
        }
        @Override public synchronized void write(VaultState value) { data = value; }
    }
    @Test(timeout=15000) public void delayedRecoveryMustNotRollBackSuccessfullyRotatedCredentials() throws Exception {
        QueuedDispatcher dispatcher = new QueuedDispatcher();
        RecoveryIoDispatcher io = new RecoveryIoDispatcher();
        CoroutineScope owner = CoroutineScopeKt.CoroutineScope(dispatcher);
        RecoveringStore store = new RecoveringStore();
        AtomicInteger refreshRequests = new AtomicInteger();
        List<String> refreshCredentials = Collections.synchronizedList(new ArrayList<>());
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            assertEquals("Only the synthetic refresh endpoint may be reached", "/api/accounts/oauth/token", request.url().encodedPath());
            assertTrue(request.body() instanceof FormBody);
            FormBody form = (FormBody) request.body();
            for (int i = 0; i < form.size(); i++) {
                if (form.name(i).equals("refresh_token")) refreshCredentials.add(form.value(i));
            }
            int number = refreshRequests.incrementAndGet();
            String body = number == 1
                ? "{\"access_token\":\"synthetic-new-access\",\"refresh_token\":\"synthetic-new-refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"resource.invoke chatgpt.tokens.use.direct\"}"
                : "{\"error\":\"invalid_grant\"}";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(number == 1 ? 200 : 400).message("local synthetic refresh only")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        try {
            AuthManager auth = new AuthManager(null, http, owner, store, io, () -> new Loopback());
            assertTrue(auth.getStorageUnavailable().getValue());
            Job recoveryA = auth.retryStorage();
            Job recoveryB = auth.retryStorage();
            assertEquals(2, dispatcher.pending.size());
            assertEquals(2, dispatcher.dispatches.get());
            dispatcher.step(); // Start A and suspend on controlled recovery IO.
            dispatcher.step(); // Start B before executing either IO runnable.
            assertEquals(2, dispatcher.executions.get());
            assertEquals(0, dispatcher.pending.size());
            assertEquals(2, io.dispatches.get());
            assertEquals(2, io.controlledDispatches.get());
            assertEquals(0, io.delegatedDispatches.get());
            assertEquals(2, io.pending.size());
            assertEquals(0, io.executions.get());
            assertEquals(1, store.reads.get());
            assertFalse(recoveryA.isCompleted());
            assertFalse(recoveryB.isCompleted());
            io.step(); // Both IO reads finish before any owner publication is run.
            io.step();
            assertEquals(2, io.executions.get());
            assertEquals(0, io.pending.size());
            assertEquals(3, store.reads.get());
            assertEquals(2, dispatcher.pending.size());
            assertEquals(4, dispatcher.dispatches.get());
            assertEquals(2, dispatcher.executions.get());
            assertTrue(auth.getStorageUnavailable().getValue());
            assertEquals(0, refreshRequests.get());
            assertTrue("Both real vault-read boundaries must finish before rotation", store.retryReadsFinished.await(5, TimeUnit.SECONDS));
            dispatcher.step(); // Publish only A; B remains queued, with its old immutable result.
            assertTrue(recoveryA.isCompleted());
            assertFalse(recoveryB.isCompleted());
            assertEquals(1, dispatcher.pending.size());
            assertFalse(auth.getStorageUnavailable().getValue());
            AuthManager.Lease first = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> auth.lease(continuation));
            assertNotNull(first);
            assertEquals(1, refreshRequests.get());
            assertEquals("Rotation must durably succeed before late recovery", "synthetic-new-refresh", store.data.getAccounts().get(0).getRefresh());
            auth.release(first);
            assertTrue("Lease rotation must delegate subsequent work to real IO", io.delegatedDispatches.get() > 0);
            assertEquals(2, io.controlledDispatches.get());
            assertEquals(1, dispatcher.pending.size());
            dispatcher.step(); // Publish the delayed pre-rotation read B.
            assertTrue(recoveryB.isCompleted());
            assertEquals(0, dispatcher.pending.size());
            Exception secondFailure = null;
            try {
                AuthManager.Lease second = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> auth.lease(continuation));
                auth.release(second);
            } catch (Exception failure) { secondFailure = failure; }
            if (refreshRequests.get() > 1) {
                assertEquals("A second request in this failing interleaving uses the old credential", "synthetic-old-refresh", refreshCredentials.get(1));
                System.out.println("OBSERVED_STALE_REFRESH_REUSE_AFTER_SUCCESSFUL_DURABLE_ROTATION=true");
            }
            assertEquals("A late recovery must not cause reuse of the consumed old refresh credential", 1, refreshRequests.get());
            assertNull("Fresh rotated credentials must remain usable after late recovery", secondFailure);
            assertEquals("Late recovery must preserve the newest durable credentials", "synthetic-new-refresh", store.data.getAccounts().get(0).getRefresh());
        } finally {
            CoroutineScopeKt.cancel(owner, null);
            Runnable remaining;
            while ((remaining = io.pending.poll()) != null) remaining.run();
            while ((remaining = dispatcher.pending.poll()) != null) remaining.run();
            http.dispatcher().cancelAll();
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
    }

    @Test(timeout=15000) public void inlineRecoveryIoDoesNotRequireAQueuedOwnerContinuation() throws Exception {
        QueuedDispatcher dispatcher = new QueuedDispatcher();
        CoroutineScope owner = CoroutineScopeKt.CoroutineScope(dispatcher);
        RecoveringStore store = new RecoveringStore();
        AtomicInteger refreshRequests = new AtomicInteger();
        List<String> refreshCredentials = Collections.synchronizedList(new ArrayList<>());
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            assertEquals("Only the synthetic refresh endpoint may be reached", "/api/accounts/oauth/token", request.url().encodedPath());
            assertTrue(request.body() instanceof FormBody);
            FormBody form = (FormBody) request.body();
            for (int i = 0; i < form.size(); i++) {
                if (form.name(i).equals("refresh_token")) refreshCredentials.add(form.value(i));
            }
            int number = refreshRequests.incrementAndGet();
            String body = number == 1
                ? "{\"access_token\":\"synthetic-new-access\",\"refresh_token\":\"synthetic-new-refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"resource.invoke chatgpt.tokens.use.direct\"}"
                : "{\"error\":\"invalid_grant\"}";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(number == 1 ? 200 : 400).message("local synthetic refresh only")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        try {
            AuthManager auth = new AuthManager(null, http, owner, store, new InlineIoDispatcher(), () -> new Loopback());
            assertTrue(auth.getStorageUnavailable().getValue());
            Job recoveryA = auth.retryStorage();
            Job recoveryB = auth.retryStorage();
            assertEquals(2, dispatcher.pending.size());
            dispatcher.step(); // Read A and publish inline before this start returns.
            dispatcher.step(); // Read B also completes without an owner continuation.
            assertEquals(3, store.reads.get());
            assertTrue(recoveryA.isCompleted());
            assertTrue(recoveryB.isCompleted());
            assertEquals(2, dispatcher.dispatches.get());
            assertEquals(2, dispatcher.executions.get());
            assertEquals(0, dispatcher.pending.size());
            assertEquals(0, refreshRequests.get());
            assertTrue("Both real vault-read boundaries must finish before rotation", store.retryReadsFinished.await(5, TimeUnit.SECONDS));
            assertEquals(de.voci.domain.ProviderState.PLAN_AUTHORIZED, auth.getState().getValue());
            assertFalse(auth.getStorageUnavailable().getValue());
            AuthManager.Lease first = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> auth.lease(continuation));
            assertNotNull(first);
            assertEquals(1, refreshRequests.get());
            assertEquals("Rotation must durably succeed before late recovery", "synthetic-new-refresh", store.data.getAccounts().get(0).getRefresh());
            auth.release(first);
            String freshAccess = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> auth.accessToken(continuation));
            assertEquals("Synthetic rotation must return fresh access credentials", "synthetic-new-access", freshAccess);
            assertEquals(List.of("synthetic-old-refresh"), refreshCredentials);
            assertEquals(0, dispatcher.pending.size());
            Exception secondFailure = null;
            try {
                AuthManager.Lease second = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> auth.lease(continuation));
                auth.release(second);
            } catch (Exception failure) { secondFailure = failure; }
            if (refreshRequests.get() > 1) {
                assertEquals("A second request in this failing interleaving uses the old credential", "synthetic-old-refresh", refreshCredentials.get(1));
                System.out.println("OBSERVED_STALE_REFRESH_REUSE_AFTER_SUCCESSFUL_DURABLE_ROTATION=true");
            }
            assertEquals("A late recovery must not cause reuse of the consumed old refresh credential", 1, refreshRequests.get());
            assertNull("Fresh rotated credentials must remain usable after late recovery", secondFailure);
            assertEquals("Late recovery must preserve the newest durable credentials", "synthetic-new-refresh", store.data.getAccounts().get(0).getRefresh());
        } finally {
            CoroutineScopeKt.cancel(owner, null);
            Runnable remaining;
            while ((remaining = dispatcher.pending.poll()) != null) remaining.run();
            http.dispatcher().cancelAll();
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
    }
}
