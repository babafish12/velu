import de.voci.auth.Loopback;
import de.voci.auth.OAuthAttempt;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.*;

/** Real local socket, no user credentials and no external service. */
public class LoopbackDeadlineRegression {
    @Test(timeout=5000)
    public void slowHeaderCannotExceedAttemptDeadline() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Loopback listener = new Loopback()) {
            OAuthAttempt attempt = new OAuthAttempt("synthetic-state", "nonce", "verifier", listener.getRedirect(), System.currentTimeMillis()+250, null);
            Future<?> result = executor.submit(() -> listener.await(attempt));
            int port = URI.create(listener.getRedirect()).getPort();
            try (Socket client = new Socket("127.0.0.1", port)) {
                Future<?> sender = executor.submit(() -> {
                    try {
                        // Every byte arrives before a per-read timeout, but total expiry must still apply.
                        for (int i=0; i<100; i++) {
                            client.getOutputStream().write('x');
                            client.getOutputStream().flush();
                            Thread.sleep(25);
                        }
                    } catch (Exception expectedAfterClose) { }
                });
                try {
                    result.get(900, TimeUnit.MILLISECONDS);
                    fail("An incomplete header must not produce successful authorization");
                } catch (ExecutionException expired) {
                    assertTrue("Expected terminal expiry", expired.getCause() instanceof IllegalArgumentException);
                } catch (TimeoutException overrun) {
                    fail("Slow header is still holding login after its 250 ms absolute deadline and 650 ms grace");
                } finally { sender.cancel(true); }
            }
        } finally { executor.shutdownNow(); }
    }
}
