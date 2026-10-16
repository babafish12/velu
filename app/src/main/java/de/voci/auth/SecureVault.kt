package de.voci.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class Registration(val client:String,val subject:String,val label:String,val access:String="",val refresh:String="",val idToken:String="",val scopes:Set<String> = emptySet(),val expiresAt:Long=0)
@Serializable data class VaultState(val host:String="urn:uuid:${UUID.randomUUID()}",val accounts:List<Registration> = emptyList(),val active:String?=null)

interface CredentialStore { fun read():VaultState; fun write(state:VaultState) }

/** Only app-private noBackupFilesDir; ciphertext authenticated with AES-GCM Keystore key. */
class SecureVault(context:Context,private val beforeCommit:()->Unit={}):CredentialStore {
    private val file=AtomicFile(File(context.noBackupFilesDir,"session.vault"))
    private val key:SecretKey get() {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("voci.session",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("voci.session",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun read():VaultState {
        if (!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists() && !File(file.baseFile.path+".new").exists()) return VaultState().also(::write)
        val bytes=file.readFully(); require(bytes.size>28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return Json.decodeFromString(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).decodeToString())
    }
    @Synchronized override fun write(state:VaultState) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
        val bytes=cipher.iv+cipher.doFinal(Json.encodeToString(state).toByteArray())
        val out=file.startWrite()
        try { out.write(bytes); beforeCommit(); file.finishWrite(out) } catch(e:Exception) { file.failWrite(out); throw e }
    }
}
