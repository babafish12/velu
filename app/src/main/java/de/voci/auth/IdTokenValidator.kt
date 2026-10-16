package de.voci.auth
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest

object IdTokenValidator {
 fun validate(token:String,keys:JWKSet,client:String,nonce:String?,nowSeconds:Long):String {
  try {
   require(token.length<=32768)
   val jwt=SignedJWT.parse(token)
   require(jwt.header.algorithm==JWSAlgorithm.RS256 && jwt.header.criticalParams.isNullOrEmpty())
   val key=keys.getKeyByKeyId(jwt.header.keyID) as? RSAKey ?: throw IllegalArgumentException("Unknown signing key")
   require(jwt.verify(RSASSAVerifier(key.toRSAPublicKey())))
   val c=jwt.jwtClaimsSet
   require(c.issuer=="https://auth.openai.com")
   require(client in c.audience)
   if(c.audience.size>1)require(c.getStringClaim("azp")==client)
   require(c.expirationTime!=null && c.expirationTime.time/1000>nowSeconds)
   require(c.issueTime!=null && c.issueTime.time/1000<=nowSeconds+5)
   require(c.notBeforeTime==null || c.notBeforeTime.time/1000<=nowSeconds+5)
   if(nonce!=null)require(MessageDigest.isEqual(nonce.toByteArray(),c.getStringClaim("nonce").orEmpty().toByteArray()))
   require(!c.subject.isNullOrBlank())
   return c.subject
  } catch(_:Exception) { throw IllegalArgumentException("ID token validation failed") }
 }
}
