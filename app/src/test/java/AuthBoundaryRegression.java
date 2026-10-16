import de.voci.auth.*;
import de.voci.domain.ProviderState;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.*;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent fixture: real product AuthManager; only disk/HTTP are injected. */
public class AuthBoundaryRegression {
    private AuthManager manager(CoroutineScope owner, OkHttpClient http, CredentialStore store) {
        return new AuthManager(null, http, owner, store, Dispatchers.getIO(), () -> new Loopback());
    }
    private static final class Store implements CredentialStore {
        private VaultState data;
        Store() {
            Registration account = new Registration("oaiapp_A", "A", "A", "synthetic-access", "", "",
                Set.of("resource.invoke", "chatgpt.tokens.use.direct"), Long.MAX_VALUE);
            data = new VaultState("urn:uuid:00000000-0000-4000-8000-000000000001", List.of(account), "oaiapp_A");
        }
        public VaultState read() { return data; }
        public void write(VaultState state) { data = state; }
    }
    @Test(timeout=5000) public void unreadableVaultMustNotCrashAccountScreen() throws Exception {
        CoroutineScope owner = CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault());
        try {
            CredentialStore unreadable = new CredentialStore() {
                public VaultState read() { throw new IllegalStateException("synthetic corrupt encrypted vault"); }
                public void write(VaultState state) { throw new IllegalStateException("must not overwrite inaccessible credentials silently"); }
            };
            AuthManager auth;
            try { auth = manager(owner, new OkHttpClient(), unreadable); }
            catch (Exception failure) {
                fail("AuthManager constructor propagates vault failure through lazy AccountScreen auth initialization instead of reporting unavailable credentials: " + failure.getClass().getSimpleName());
                return;
            }
            assertFalse("Unreadable credentials cannot authorize", auth.getState().getValue() == ProviderState.READY || auth.getState().getValue() == ProviderState.PLAN_AUTHORIZED);
        } finally { CoroutineScopeKt.cancel(owner, null); }
    }
    @Test(timeout=5000) public void releasingOtherRequestMustNotUntrackOpenResponse() throws Exception {
        CoroutineScope owner = CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault());
        AtomicReference<Call> streamCall = new AtomicReference<>();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            if (request.url().encodedPath().equals("/stream")) streamCall.set(chain.call());
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("local synthetic transport").body(ResponseBody.create("not-yet-consumed", MediaType.get("text/plain"))).build();
        }).build();
        Response stream = null;
        try {
            AuthManager auth = manager(owner, http, new Store());
            AuthManager.Lease lease = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> auth.lease(continuation));
            stream = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> auth.request(lease, new Request.Builder().url("https://api.openai.com/stream").build(), continuation));
            AuthManager.Lease catalogLease = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> auth.lease(continuation));
            Response catalog = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> auth.request(catalogLease, new Request.Builder().url("https://api.openai.com/catalog").build(), continuation));
            catalog.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (http.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals("Both onResponse callbacks must have returned", 0, http.dispatcher().runningCallsCount());
            auth.release(catalogLease); // Completion of the catalog must not detach an open response body.
            auth.logout();
            assertNotNull(streamCall.get());
            assertTrue("Logout must cancel an unfinished streaming Call even after another request is released", streamCall.get().isCanceled());
        } finally {
            if (stream != null) stream.close();
            CoroutineScopeKt.cancel(owner, null);
            http.dispatcher().cancelAll();
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
    }
}
