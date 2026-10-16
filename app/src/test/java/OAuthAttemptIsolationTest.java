import de.voci.auth.OAuthAttempt;
import de.voci.auth.Loopback;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class OAuthAttemptIsolationTest {
    private static final String BASE = "http://127.0.0.1:1234/auth/callback";
    private static final String QUERY = "?state=correct-state&code=synthetic-code&client_id=oaiapp_test";
    private OAuthAttempt attempt() {
        return new OAuthAttempt("correct-state", "synthetic-nonce", "synthetic-verifier", BASE, 10000L, null);
    }
    private void reject(OAuthAttempt a, String url) {
        try { a.consume(url, 0L); fail("Invalid callback was accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void validCallbackStillWorks() {
        assertEquals("synthetic-code", attempt().consume(BASE + QUERY, 0L).getFirst());
    }
    @Test public void validCallbackCannotBeReplayed() {
        OAuthAttempt a = attempt(); a.consume(BASE + QUERY, 0L); reject(a, BASE + QUERY);
    }
    @Test public void wrongStateMustNotConsumeLegitimateAttempt() {
        OAuthAttempt a = attempt(); reject(a, BASE + QUERY.replace("correct-state", "attacker-state"));
        assertEquals("synthetic-code", a.consume(BASE + QUERY, 0L).getFirst());
    }
    @Test public void duplicateStateMustNotConsumeLegitimateAttempt() {
        OAuthAttempt a = attempt(); reject(a, BASE + QUERY + "&state=extra");
        assertEquals("synthetic-code", a.consume(BASE + QUERY, 0L).getFirst());
    }
    @Test public void wrongPathMustNotConsumeLegitimateAttempt() {
        OAuthAttempt a = attempt(); reject(a, BASE.replace("/auth/callback", "/wrong") + QUERY);
        assertEquals("synthetic-code", a.consume(BASE + QUERY, 0L).getFirst());
    }
    private void request(URI uri, String query) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", uri.getPort())) {
            socket.setSoTimeout(3000);
            String raw = "GET /auth/callback" + query + " HTTP/1.1\r\nHost: 127.0.0.1:" + uri.getPort() + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.getInputStream().readAllBytes();
        }
    }
    @Test(timeout=10000) public void listenerMustRemainAvailableAfterWrongState() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Loopback loopback = new Loopback()) {
            URI uri = URI.create(loopback.getRedirect());
            OAuthAttempt a = new OAuthAttempt("correct-state", "nonce", "verifier", loopback.getRedirect(), System.currentTimeMillis()+30000, null);
            Future<kotlin.Pair<String,String>> result = executor.submit(() -> loopback.await(a));
            request(uri, QUERY.replace("correct-state", "attacker-state"));
            try {
                result.get(100, TimeUnit.MILLISECONDS);
                fail("Invalid callback terminated pending login");
            } catch (TimeoutException expected) {
                // Listener is still awaiting the real callback.
            }
            request(uri, QUERY);
            assertEquals("synthetic-code", result.get(3,TimeUnit.SECONDS).getFirst());
        } finally { executor.shutdownNow(); }
    }
}
