package de.voci

import androidx.test.platform.app.InstrumentationRegistry
import de.voci.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real installed/source APKs and PackageManager; no fabricated PackageInfo/signers.
 * No install/permission mutation. A same-signer NEWER archive remains a Parent acceptance fixture.
 */
class R7UpdateArchiveTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualInstalledSourceArchiveHasSameNonemptySignerSetAndIdentity() {
        val verifier = AndroidArchiveVerifier(context)
        val installed = verifier.installed()
        val archive = verifier.inspect(File(context.applicationInfo.sourceDir))
        assertEquals("de.voci", archive.applicationId)
        assertEquals(installed, archive)
        assertTrue(archive.signers.isNotEmpty())
        assertTrue(archive.signers.all { Regex("[0-9a-f]{64}").matches(it) })
    }
    @Test fun actualEqualArchiveAndFalselyClaimedNewerArchiveAreRejected() = runBlocking {
        val verifier = AndroidArchiveVerifier(context)
        val file = File(context.applicationInfo.sourceDir)
        val candidate = candidate(file, verifier)
        expectVerification { verifier.verify(file, candidate) }
        expectVerification { verifier.verify(file, candidate.copy(versionCode = candidate.versionCode + 1)) }
        expectVerification { verifier.verify(file, candidate.copy(applicationId = "de.other")) }
        expectVerification { verifier.verify(file, candidate.copy(versionName = "9.9.9")) }
        expectVerification { verifier.verify(file, candidate.copy(minSdk = candidate.minSdk + 1)) }
    }
    @Test fun realArchiveHashLengthAndCorruptionAreRejected() = runBlocking {
        val verifier = AndroidArchiveVerifier(context)
        val source = File(context.applicationInfo.sourceDir)
        val candidate = candidate(source, verifier)
        expectVerification { verifier.verify(source, candidate.copy(sha256 = "00".repeat(32))) }
        expectVerification { verifier.verify(source, candidate.copy(sizeBytes = source.length() + 1)) }
        val owned = File(context.cacheDir, "r7-update-archive-${UUID.randomUUID()}")
        check(owned.mkdir())
        try {
            val corrupt = File(owned, "corrupt.apk")
            corrupt.writeBytes(byteArrayOf(0x50, 0x4b, 0, 0))
            expectVerification { verifier.inspect(corrupt) }
        } finally { check(owned.deleteRecursively()) }
    }
    @Test fun actualInstrumentationArchiveCannotReplaceTargetPackage() = runBlocking {
        val verifier = AndroidArchiveVerifier(context)
        val testApk = File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        assertTrue("FIXTURE: instrumentation source APK must exist", testApk.isFile)
        @Suppress("DEPRECATION")
        val metadata = context.packageManager.getPackageArchiveInfo(testApk.absolutePath,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw AssertionError("FIXTURE: PackageManager must parse instrumentation APK metadata")
        assertEquals("FIXTURE: actual instrumentation package", "de.voci.test", metadata.packageName)
        assertEquals("FIXTURE: installed target package", "de.voci", verifier.installed().applicationId)
        assertNotEquals("FIXTURE: instrumentation is a different package", context.packageName, metadata.packageName)
        assertNull("FIXTURE: instrumentation APK has no versionName", metadata.versionName)
        assertEquals("FIXTURE: instrumentation APK has no versionCode", 0L, metadata.longVersionCode)

        // This real APK has malformed version identity. It cannot supply a valid ArchiveIdentity
        // and does NOT isolate well-formed different-package rejection (see the native controls).
        expectVerification { verifier.inspect(testApk) }
        val digest = MessageDigest.getInstance("SHA-256")
        testApk.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
        }
        val target = candidate(File(context.applicationInfo.sourceDir), verifier)
        val claimsTarget = target.copy(versionCode = target.versionCode + 1, sizeBytes = testApk.length(),
            sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        expectVerification { verifier.verify(testApk, claimsTarget) }
    }
    private fun candidate(file: File, verifier: AndroidArchiveVerifier): ReleaseCandidate {
        val identity = verifier.inspect(file)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
        }
        return ReleaseCandidate("https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/v0.2.0/velu.apk",
            Math.toIntExact(identity.versionCode), identity.versionName, identity.applicationId, identity.minSdk,
            file.length(), digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, "v0.2.0")
    }
    private suspend fun expectVerification(block: suspend () -> Unit) {
        try { block(); fail("real archive must be refused") }
        catch (e: UpdateFailure) { assertEquals(UpdateProblem.VERIFICATION, e.problem) }
    }
}
