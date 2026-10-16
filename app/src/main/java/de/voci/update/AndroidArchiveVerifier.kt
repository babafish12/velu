package de.voci.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class ArchiveIdentity(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val signers: Set<String>
)

/** No signer history fallback: both current signer sets must be present and exactly equal. */
class AndroidArchiveVerifier(context: Context) : ArchiveVerifier {
    private val app = context.applicationContext
    private val pm = app.packageManager
    @Suppress("DEPRECATION")
    fun installed(): ArchiveIdentity = identity(pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
    @Suppress("DEPRECATION")
    fun inspect(file: File): ArchiveIdentity {
        requireUpdate(file.isFile, UpdateProblem.VERIFICATION)
        val info = try { pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) }
        catch (_: RuntimeException) { null }
        return identity(info ?: throw UpdateFailure(UpdateProblem.VERIFICATION))
    }
    private fun identity(info: PackageInfo): ArchiveIdentity {
        val signatures = info.signingInfo?.apkContentsSigners
        requireUpdate(!signatures.isNullOrEmpty(), UpdateProblem.VERIFICATION)
        val signers = signatures!!.map {
            val bytes = it.toByteArray()
            requireUpdate(bytes.isNotEmpty(), UpdateProblem.VERIFICATION)
            // Ensure certificate material parses, beyond merely hashing arbitrary signature bytes.
            try {
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(bytes.inputStream())
            } catch (_: java.security.cert.CertificateException) { throw UpdateFailure(UpdateProblem.VERIFICATION) }
            MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        }.toSet()
        requireUpdate(signers.size == signatures.size, UpdateProblem.VERIFICATION)
        val name = info.versionName ?: throw UpdateFailure(UpdateProblem.VERIFICATION)
        val sdk = info.applicationInfo?.minSdkVersion ?: throw UpdateFailure(UpdateProblem.VERIFICATION)
        requireUpdate(info.longVersionCode > 0 && name.isNotBlank(), UpdateProblem.VERIFICATION)
        return ArchiveIdentity(info.packageName, info.longVersionCode, name, sdk, signers)
    }
    override suspend fun verify(file: File, candidate: ReleaseCandidate) = withContext(Dispatchers.IO) {
        try {
            verifyBytes(file, candidate)
            coroutineContext.ensureActive()
            val archive = inspect(file)
            val installed = installed()
            requireUpdate(installed.applicationId == "de.voci" && archive.applicationId == installed.applicationId &&
                archive.applicationId == candidate.applicationId && archive.versionCode == candidate.versionCode.toLong() &&
                archive.versionCode > installed.versionCode && archive.versionName == candidate.versionName &&
                archive.minSdk == candidate.minSdk && archive.minSdk <= android.os.Build.VERSION.SDK_INT &&
                archive.signers == installed.signers, UpdateProblem.VERIFICATION)
            // Detect mutations while PackageManager parsed the archive as well.
            verifyBytes(file, candidate)
            coroutineContext.ensureActive()
        } catch (e: java.io.IOException) { throw UpdateFailure(UpdateProblem.VERIFICATION) }
    }
}
