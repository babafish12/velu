package de.voci

import de.voci.auth.OAuthAttempt
import org.junit.Assert.*
import org.junit.Test

class AuthProtocolTest {
    private fun attempt(client: String? = null) = OAuthAttempt("state", "nonce", "verifier", "http://127.0.0.1:1234/auth/callback", 1000, client)
    private val valid = "http://127.0.0.1:1234/auth/callback?state=state&code=c&client_id=oaiapp_test"
    private fun rejected(f: () -> Unit) { try { f(); fail("Must reject") } catch (_: IllegalArgumentException) {} }
    @Test fun validRegistration() { assertEquals("c" to "oaiapp_test", attempt().consume(valid, 0)) }
    @Test fun wrongState() = rejected { attempt().consume(valid.replace("state=state","state=other"),0) }
    @Test fun expired() = rejected { attempt().consume(valid,1000) }
    @Test fun replay() { val a=attempt(); a.consume(valid,0); rejected { a.consume(valid,0) } }
    @Test fun wrongHost() = rejected { attempt().consume(valid.replace("127.0.0.1","localhost"),0) }
    @Test fun wrongPath() = rejected { attempt().consume(valid.replace("/auth/callback","/callback"),0) }
    @Test fun wrongPort() = rejected { attempt().consume(valid.replace(":1234",":4321"),0) }
    @Test fun duplicateState() = rejected { attempt().consume("$valid&state=state",0) }
    @Test fun missingIssuedClient() = rejected { attempt().consume(valid.substringBefore("&client_id"),0) }
    @Test fun dynamicNotIssued() = rejected { attempt().consume(valid.replace("oaiapp_test","dynamic_agent_client"),0) }
    @Test fun changedReturningClient() = rejected { attempt("oaiapp_other").consume(valid,0) }
    @Test fun returningClientMayBeOmitted() { assertEquals("oaiapp_test",attempt("oaiapp_test").consume(valid.substringBefore("&client_id"),0).second) }
    @Test fun denied() = rejected { attempt().consume("$valid&error=access_denied",0) }
}
