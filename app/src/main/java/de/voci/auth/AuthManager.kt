package de.voci.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nimbusds.jose.jwk.JWKSet
import de.voci.domain.ProviderState
import de.voci.ai.ProviderFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ForwardingSource
import okio.buffer
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64

class AuthManager(private val context:Context?,private val http:OkHttpClient,private val scope:CoroutineScope,private val vault:CredentialStore=SecureVault(requireNotNull(context)),private val io:CoroutineDispatcher=Dispatchers.IO,private val loopbackFactory:()->Loopback={Loopback()}) {
    private val rotations=CoroutineScope(scope.coroutineContext+SupervisorJob(scope.coroutineContext[Job]))
    private val lock=Mutex()
    private val gate=Any()
    private var storageRevision=0L
    private val revoked=mutableMapOf<String,Long>()
    private val calls=mutableMapOf<Call,Lease>()
    private val generation=SessionEpoch()
    @Volatile private var storageReadable=true
    @Volatile private var persistenceFailure=false
    private var data=try { vault.read() } catch(_:Exception) { storageReadable=false;VaultState(host="") }
    private var listener:Loopback?=null
    private var login:Job?=null
    val accounts=MutableStateFlow(data.accounts.map { it.copy(access="",refresh="",idToken="") })
    val activeLabel=MutableStateFlow(data.accounts.find { it.client==data.active }?.label.orEmpty())
    val state=MutableStateFlow(if(storageReadable)stateOf(active()) else ProviderState.DISCONNECTED)
    val storageUnavailable=MutableStateFlow(!storageReadable)
    val message=MutableStateFlow<String?>(if(storageReadable)null else "Zugangsdaten nicht lesbar. Speicher prüfen und erneut versuchen.")
    private fun active()=data.accounts.find { it.client==data.active }
    private fun stateOf(r:Registration?)=when { persistenceFailure->ProviderState.DISCONNECTED; r==null || r.access.isBlank()->ProviderState.SIGNED_OUT; ("chatgpt.tokens.use.direct" !in r.scopes || "resource.invoke" !in r.scopes)->ProviderState.IDENTITY_VERIFIED; else->ProviderState.PLAN_AUTHORIZED }
    private fun save() { try { vault.write(data);storageRevision++;persistenceFailure=false } catch(e:Exception) { persistenceFailure=true;throw e }; accounts.value=data.accounts.map { it.copy(access="",refresh="",idToken="") };activeLabel.value=active()?.label.orEmpty() }
    fun signIn(client:String?=null,consent:Boolean=false) {
        if(!storageReadable) { message.value="Zugangsdaten nicht lesbar. Erneut prüfen.";return }
        cancelLogin(); val epoch=generation.current(); val selected=data.accounts.find { it.client==client }
        state.value=ProviderState.SIGNING_IN;message.value=null
        login=scope.launch {
            var owned:Loopback?=null
            try {
                withContext(io) { owned=loopbackFactory() }; val callback=requireNotNull(owned);listener=callback
                val attempt=OAuthAttempt(random(),random(),random(64),callback.redirect,System.currentTimeMillis()+300000,selected?.client)
                val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray()))
                val url=HttpUrl.Builder().scheme("https").host("auth.openai.com").addPathSegments("api/accounts/authorize")
                    .addQueryParameter("client_id",selected?.client?:"dynamic_agent_client")
                    .addQueryParameter("ext_agent_host_id",data.host).addQueryParameter("response_type","code")
                    .addQueryParameter("redirect_uri",callback.redirect).addQueryParameter("scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                    .addQueryParameter("resource","https://api.openai.com/v1").addQueryParameter("state",attempt.state)
                    .addQueryParameter("nonce",attempt.nonce).addQueryParameter("code_challenge_method","S256").addQueryParameter("code_challenge",challenge)
                if(selected==null) url.addQueryParameter("agent_name_hint","Velu")
                if(!selected?.idToken.isNullOrEmpty()) url.addQueryParameter("id_token_hint",selected!!.idToken)
                if(consent) url.addQueryParameter("prompt","consent")
                requireNotNull(context).startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url.build().toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val (code,issued)=withContext(io) { callback.use { it.await(attempt) } }
                val tokens=postToken(mapOf("grant_type" to "authorization_code","code" to code,"client_id" to issued,"code_verifier" to attempt.verifier,"redirect_uri" to attempt.redirect,"resource" to "https://api.openai.com/v1"))
                val id=tokens.string("id_token")
                val keys=withContext(io) { http.newCall(Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build()).execute().use { require(it.isSuccessful);JWKSet.parse(it.body.string()) } }
                val subject=IdTokenValidator.validate(id,keys,issued,attempt.nonce,System.currentTimeMillis()/1000)
                require(selected==null || selected.subject==subject)
                val record=record(tokens,issued,subject,selected)
                synchronized(gate) { if(generation.current()!=epoch) return@synchronized;data=data.copy(accounts=data.accounts.filterNot { it.client==issued }+record,active=issued);save();state.value=stateOf(record) }
            } catch(e:CancellationException) { throw e } catch(_:Exception) { if(generation.current()==epoch) { state.value=stateOf(active());message.value="Anmeldung nicht abgeschlossen. Erneut versuchen." } }
            finally { owned?.close(); if(listener===owned)listener=null }
        }
    }
    private fun invalidateSelection() {
        generation.invalidate(); listener?.close();listener=null;login?.cancel()
        calls.keys.toList().forEach { it.cancel() };calls.clear()
    }
    fun cancelLogin() = synchronized(gate) { invalidateSelection();state.value=if(storageReadable)stateOf(active()) else ProviderState.DISCONNECTED }
    class Lease internal constructor(val epoch:Long,val client:String,internal val token:String)
    suspend fun lease():Lease {
        val (epoch,client)=synchronized(gate) {
            if(!storageReadable || persistenceFailure)throw ProviderFailure("credential_storage")
            val r=active()?:throw ProviderFailure("signed_out")
            if(r.access.isBlank())throw ProviderFailure("signed_out")
            generation.current() to r.client
        }
        // Application-owned bounded rotation: cancelling a screen cannot discard a consumed refresh token.
        val token=rotations.async(io) {
            withTimeout(155000) { lock.withLock { refreshAccount(client) } }
        }.await()
        return synchronized(gate) { checkLease(epoch,client);Lease(epoch,client,token) }
    }
    suspend fun accessToken():String=lease().token
    private suspend fun refreshAccount(client:String):String {
        var r:Registration
        val revision:Long
        synchronized(gate) {
            r=data.accounts.find { it.client==client }?:throw ProviderFailure("signed_out")
            revision=revoked[client]?:0
            if(r.access.isBlank())throw ProviderFailure("signed_out")
            if("chatgpt.tokens.use.direct" !in r.scopes || "resource.invoke" !in r.scopes)throw ProviderFailure("plan_permission")
        }
        if(r.expiresAt<=System.currentTimeMillis()+60000) {
            val tokens=try { postToken(mapOf("grant_type" to "refresh_token","client_id" to r.client,"refresh_token" to r.refresh,"resource" to "https://api.openai.com/v1")) }
            catch(e:ProviderFailure) {
                if(e.kind in setOf("invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired","refresh_token_invalidated","refresh_token_reused")) synchronized(gate) {
                    if((revoked[client]?:0)==revision) {
                        data=data.copy(accounts=data.accounts.map { if(it.client==client)it.signedOut() else it });save()
                        if(data.active==client) { generation.invalidate();state.value=ProviderState.SIGNED_OUT }
                    }
                }
                throw e
            }
            tokens["id_token"]?.jsonPrimitive?.content?.let { renewed->
                val keys=withContext(io) { http.newCall(Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build()).execute().use { require(it.isSuccessful);JWKSet.parse(it.body.string()) } }
                require(IdTokenValidator.validate(renewed,keys,r.client,null,System.currentTimeMillis()/1000)==r.subject)
            }
            synchronized(gate) {
                if((revoked[client]?:0)!=revision)throw CancellationException("Account revoked")
                r=record(tokens,r.client,r.subject,r)
                data=data.copy(accounts=data.accounts.map { if(it.client==client)r else it });save()
                if(data.active==client)state.value=stateOf(r)
            }
        }
        if("chatgpt.tokens.use.direct" !in r.scopes || "resource.invoke" !in r.scopes)throw ProviderFailure("plan_permission")
        return r.access
    }
    private fun checkLease(epoch:Long,client:String) {
        if(epoch!=generation.current() || data.active!=client || active()?.access.isNullOrBlank())throw CancellationException("Account changed")
    }
    fun validate(lease:Lease)=synchronized(gate) { checkLease(lease.epoch,lease.client) }
    fun authorizationEpoch()=generation.current()
    fun completeInference(epoch:Long)=synchronized(gate) { if(epoch!=generation.current() || active()?.access.isNullOrBlank())throw CancellationException("Session changed");state.value=ProviderState.READY }
    fun publishFailure(epoch:Long,e:Exception)=synchronized(gate) {
        if(epoch!=generation.current())throw CancellationException("Session changed")
        state.value=when((e as? ProviderFailure)?.kind) {
            "limit","subscription_sharing_usage_limit_exceeded"->ProviderState.LIMIT
            "signed_out"->ProviderState.SIGNED_OUT
            "plan_permission"->ProviderState.IDENTITY_VERIFIED
            else->ProviderState.DISCONNECTED
        }
    }
    /** Validation and network enqueue share the same gate as selection/logout. */
    suspend fun request(lease:Lease,request:Request):Response=suspendCancellableCoroutine { cont->
        synchronized(gate) {
            try {
                checkLease(lease.epoch,lease.client)
                val call=http.newCall(request.newBuilder().header("Authorization","Bearer ${lease.token}").build())
                calls[call]=lease
                cont.invokeOnCancellation { call.cancel();synchronized(gate) { calls.remove(call) } }
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:java.io.IOException) { synchronized(gate) { calls.remove(call) };if(cont.isActive)cont.resumeWith(Result.failure(e)) }
                    override fun onResponse(call:Call,response:Response) {
                        synchronized(gate) {
                            try {
                                checkLease(lease.epoch,lease.client)
                                val body=response.body
                                val source=object:ForwardingSource(body.source()) {
                                    override fun close() { try { super.close() } finally { synchronized(gate) { calls.remove(call) } } }
                                }.buffer()
                                val owned=response.newBuilder().body(object:ResponseBody() {
                                    override fun contentType()=body.contentType()
                                    override fun contentLength()=body.contentLength()
                                    override fun source()=source
                                }).build()
                                cont.resume(owned) { _,r,_->r.close() }
                            }
                            catch(e:Exception) { response.close();calls.remove(call);if(cont.isActive)cont.resumeWith(Result.failure(e)) }
                        }
                    }
                })
            } catch(e:Exception) { cont.resumeWith(Result.failure(e)) }
        }
    }
    fun release(lease:Lease)=synchronized(gate) {
        val owned=calls.filterValues { it===lease }.keys.toList()
        owned.forEach { it.cancel();calls.remove(it) }
    }
    /** The cancellation watcher owns the entire response-body lifetime, including blocking reads. */
    suspend fun <T> withLease(block:suspend(Lease)->T):T=coroutineScope {
        val lease=lease()
        val watcher=launch(io,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { release(lease) } }
        try { block(lease) } finally { watcher.cancel();release(lease) }
    }
    fun retryStorage():Job {
        val (epoch,revision)=synchronized(gate) { generation.current() to storageRevision }
        return scope.launch {
            val restored=withContext(io) { runCatching { vault.read() } }
            synchronized(gate) {
                if(epoch!=generation.current() || revision!=storageRevision)return@synchronized
                restored.onSuccess { value->
                    data=value;storageRevision++;storageReadable=true;storageUnavailable.value=false
                    accounts.value=data.accounts.map { it.copy(access="",refresh="",idToken="") };activeLabel.value=active()?.label.orEmpty()
                    state.value=stateOf(active());message.value=null
                }.onFailure { storageReadable=false;storageUnavailable.value=true;state.value=ProviderState.DISCONNECTED;message.value="Zugangsdaten nicht lesbar. Speicher prüfen und erneut versuchen." }
            }
        }
    }
    fun select(client:String) = synchronized(gate) {
        require(data.accounts.any { it.client==client })
        invalidateSelection();data=data.copy(active=client);try { save();state.value=stateOf(active()) } catch(_:Exception) { state.value=ProviderState.DISCONNECTED;message.value="Kontowechsel nicht gespeichert. Speicher prüfen." }
    }
    fun logout() {
        if(!storageReadable) { message.value="Zugangsdaten nicht lesbar. Erneut prüfen.";return }
        val old:Registration?
        var durable=true
        synchronized(gate) {
            invalidateSelection();old=active()
            if(old!=null)revoked[old.client]=(revoked[old.client]?:0)+1
            data=data.copy(accounts=data.accounts.map { if(it.client==old?.client)it.signedOut() else it })
            try { save();state.value=ProviderState.SIGNED_OUT }
            catch(_:Exception) { durable=false;state.value=ProviderState.DISCONNECTED;message.value="Abmelden nicht gespeichert. Erneut versuchen und Zugriff in ChatGPT trennen." }
        }
        scope.launch {
            if(!old?.refresh.isNullOrBlank()) {
                val revoked=withContext(io) { runCatching {
                    val discovery=http.newCall(Request.Builder().url("https://auth.openai.com/.well-known/openid-configuration").build()).execute().use { require(it.isSuccessful);Json.parseToJsonElement(it.body.string()).jsonObject }
                    val endpoint=discovery.string("revocation_endpoint");require(endpoint.startsWith("https://auth.openai.com/"))
                    http.newCall(Request.Builder().url(endpoint).post(FormBody.Builder().add("token",old!!.refresh).add("token_type_hint","refresh_token").add("client_id",old.client).build()).build()).execute().use { it.code==200 }
                }.getOrDefault(false) }
                if(!revoked && durable && state.value==ProviderState.SIGNED_OUT)message.value="Lokal abgemeldet. Zugriff in ChatGPT-Einstellungen trennen."
            }
        }
    }
    private suspend fun postToken(fields:Map<String,String>):JsonObject=withContext(io) {
        val body=FormBody.Builder().apply { fields.forEach { (k,v)->add(k,v) } }.build()
        http.newCall(Request.Builder().url("https://auth.openai.com/api/accounts/oauth/token").post(body).build()).execute().use { response->
            if(!response.isSuccessful) {
                val code=runCatching { Json.parseToJsonElement(response.body.string().take(16384)).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw ProviderFailure(if(code in setOf("invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired","refresh_token_invalidated","refresh_token_reused","invalid_client"))code!! else "token_http_${response.code}")
            }
            Json.parseToJsonElement(response.body.string()).jsonObject
        }
    }
    private fun record(t:JsonObject,client:String,sub:String,old:Registration?):Registration {
        require(t.string("token_type").equals("Bearer",true));val access=t.string("access_token");require(access.isNotBlank())
        val scopes=t["scope"]?.jsonPrimitive?.content?.split(" ")?.filter { it.isNotBlank() }?.toSet() ?: old?.scopes ?: emptySet()
        return Registration(client,sub,old?.label?:"Konto ${data.accounts.size+1} · ${client.takeLast(6)}",access,t["refresh_token"]?.jsonPrimitive?.content?:old?.refresh.orEmpty(),t["id_token"]?.jsonPrimitive?.content?:old?.idToken.orEmpty(),scopes,System.currentTimeMillis()+t.getValue("expires_in").jsonPrimitive.long.coerceIn(1,86400)*1000)
    }
    private fun random(n:Int=32)=ByteArray(n).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
internal fun JsonObject.string(key:String)=getValue(key).jsonPrimitive.content

private fun Registration.signedOut()=copy(access="",refresh="",idToken="",scopes=emptySet(),expiresAt=0)
