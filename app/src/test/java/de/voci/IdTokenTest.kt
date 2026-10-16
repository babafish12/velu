package de.voci
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.*
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.*
import de.voci.auth.IdTokenValidator
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class IdTokenTest {
    private val key = RSAKeyGenerator(2048).keyID("test").generate()
    private fun token(issuer:String="https://auth.openai.com", audience:String="oaiapp_test", nonce:String="nonce", exp:Long=200, signing:RSAKey=key):String {
        val claims=JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("subject").issueTime(Date(50000)).expirationTime(Date(exp*1000)).claim("nonce",nonce).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims).apply { sign(RSASSASigner(signing)) }.serialize()
    }
    private fun validate(t:String)=IdTokenValidator.validate(t,JWKSet(key.toPublicJWK()),"oaiapp_test","nonce",100)
    private fun reject(t:String) { try { validate(t); fail() } catch (_: IllegalArgumentException) {} }
    @Test fun validSignature() { assertEquals("subject",validate(token())) }
    @Test fun issuerRejected()=reject(token(issuer="https://evil.example"))
    @Test fun audienceRejected()=reject(token(audience="other"))
    @Test fun nonceRejected()=reject(token(nonce="other"))
    @Test fun expiredRejected()=reject(token(exp=99))
    @Test fun signatureRejected()=reject(token(signing=RSAKeyGenerator(2048).generate()))
    @Test fun unsignedRejected()=reject("eyJhbGciOiJub25lIn0.e30.")
}
