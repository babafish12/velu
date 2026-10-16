package de.voci

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.update.AndroidArchiveVerifier
import de.voci.update.ArchiveIdentity
import de.voci.update.ReleaseCandidate
import de.voci.update.ReleasePolicy
import de.voci.update.UpdateFailure
import de.voci.update.UpdateProblem
import de.voci.update.verifyBytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Parent supplies three actual signed, manifest-only APKs. These tests only read/verify them;
 * they never install, grant permissions or remove files. This is not self-update acceptance.
 */
class R7NativeUpdateArchiveTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realNewerSameSignerArchiveIsAccepted() = runBlocking {
        val fixture = fixture("same-signer-newer.apk")
        assertEquals("FIXTURE: positive archive must target the installed package",
            fixture.installed.applicationId, fixture.archive.applicationId)
        assertEquals("FIXTURE: positive archive must have the installed current signers",
            fixture.installed.signers, fixture.archive.signers)
        // Real positive control: a failure here must fail the test, never be treated as rejection.
        fixture.verifier.verify(fixture.file, fixture.candidate)
    }

    @Test fun realNewerDifferentSignerArchiveIsRejected() = runBlocking {
        val fixture = fixture("wrong-signer-newer.apk")
        assertEquals("FIXTURE: wrong-signer archive must still target the installed package",
            fixture.installed.applicationId, fixture.archive.applicationId)
        assertNotEquals("FIXTURE: archive must actually have different current signers",
            fixture.installed.signers, fixture.archive.signers)
        assertTrue("FIXTURE: different test certificate must not include an installed signer",
            fixture.archive.signers.intersect(fixture.installed.signers).isEmpty())
        // All version/name/SDK/byte conditions were established by fixture(); only signers differ.
        expectVerification { fixture.verifier.verify(fixture.file, fixture.candidate) }
    }

    @Test fun realNewerDifferentPackageArchiveIsRejected() = runBlocking {
        val fixture = fixture("wrong-package-newer.apk")
        assertEquals("FIXTURE: actual different archive package", "de.voci.fixture.other",
            fixture.archive.applicationId)
        assertNotEquals("FIXTURE: archive package must differ from installed target",
            fixture.installed.applicationId, fixture.archive.applicationId)
        assertEquals("FIXTURE: wrong-package archive must use the installed current signers",
            fixture.installed.signers, fixture.archive.signers)
        assertEquals("FIXTURE: candidate must claim target, not the foreign archive package",
            "de.voci", fixture.candidate.applicationId)
        expectVerification { fixture.verifier.verify(fixture.file, fixture.candidate) }
    }

    private data class Fixture(
        val file: File,
        val verifier: AndroidArchiveVerifier,
        val installed: ArchiveIdentity,
        val archive: ArchiveIdentity,
        val candidate: ReleaseCandidate
    )

    private fun fixture(name: String): Fixture {
        assertEquals("FIXTURE: instrumentation must target VOCI", "de.voci", context.packageName)
        val file = fixtureFile(name)
        val verifier = AndroidArchiveVerifier(context)
        val installed = fixtureRead("installed target identity") { verifier.installed() }
        val archive = fixtureRead("valid signed archive identity for $name") { verifier.inspect(file) }
        assertEquals("FIXTURE: installed application ID", "de.voci", installed.applicationId)
        assertTrue("FIXTURE: installed version must permit a newer Int versionCode",
            installed.versionCode in 1L until Int.MAX_VALUE.toLong())
        assertEquals("FIXTURE: archive must really be installedVersionCode + 1",
            installed.versionCode + 1, archive.versionCode)
        assertEquals("FIXTURE: archive versionName", "0.2.1", archive.versionName)
        assertEquals("FIXTURE: archive minSdk", 28, archive.minSdk)
        assertTrue("FIXTURE: archive must be compatible with this device", archive.minSdk <= Build.VERSION.SDK_INT)
        for (identity in listOf(installed, archive)) {
            assertTrue("FIXTURE: real parsed current signer set must be nonempty", identity.signers.isNotEmpty())
            assertTrue("FIXTURE: signer fingerprints must be SHA-256",
                identity.signers.all { Regex("[a-f0-9]{64}").matches(it) })
        }
        val size = file.length()
        assertTrue("FIXTURE: actual APK size must satisfy verifier bounds", size in 1..ReleasePolicy.APK_LIMIT)
        val hash = fixtureRead("hash actual $name bytes") {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32768)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val tag = "v${archive.versionName}"
        // Canonical synthetic release metadata only; no URL is requested by these tests.
        val candidate = ReleaseCandidate(
            "https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/$tag/velu.apk",
            Math.toIntExact(archive.versionCode), archive.versionName, "de.voci", archive.minSdk,
            size, hash, tag
        )
        assertEquals("FIXTURE: candidate package must match installed target",
            installed.applicationId, candidate.applicationId)
        assertEquals("FIXTURE: candidate version must match archive", archive.versionCode, candidate.versionCode.toLong())
        assertEquals("FIXTURE: candidate name must match archive", archive.versionName, candidate.versionName)
        assertEquals("FIXTURE: candidate SDK must match archive", archive.minSdk, candidate.minSdk)
        fixtureRead("candidate size/hash must match actual $name bytes") { verifyBytes(file, candidate) }
        return Fixture(file, verifier, installed, archive, candidate)
    }

    private fun fixtureFile(name: String): File = fixtureRead("owned native archive directory/$name") {
        val basename = InstrumentationRegistry.getArguments().getString("r7_archive_fixture_dir")
            ?: throw AssertionError("FIXTURE: missing r7_archive_fixture_dir instrumentation argument")
        assertTrue("FIXTURE: directory argument must be an exact owned basename",
            Regex("r7-native-archive-[a-f0-9]{32}").matches(basename))
        assertTrue("FIXTURE: only the three named archives may be read",
            name in setOf("same-signer-newer.apk", "wrong-signer-newer.apk", "wrong-package-newer.apk"))
        val cache = context.cacheDir.canonicalFile
        val directory = File(cache, basename)
        assertFalse("FIXTURE: owned directory must not be a symlink", Files.isSymbolicLink(directory.toPath()))
        assertEquals("FIXTURE: owned directory must resolve directly under target cache",
            directory.absoluteFile, directory.canonicalFile)
        assertEquals("FIXTURE: directory parent must be target cache", cache, directory.canonicalFile.parentFile)
        assertTrue("FIXTURE: Parent must supply the owned directory", directory.isDirectory)
        val file = File(directory, name)
        assertFalse("FIXTURE: archive must not be a symlink", Files.isSymbolicLink(file.toPath()))
        assertEquals("FIXTURE: archive must resolve within the owned directory", file.absoluteFile, file.canonicalFile)
        assertEquals("FIXTURE: archive parent must be the owned directory", directory, file.canonicalFile.parentFile)
        assertTrue("FIXTURE: Parent must supply readable $name", file.isFile && file.canRead())
        file
    }

    private fun <T> fixtureRead(label: String, block: () -> T): T = try { block() }
    catch (error: Exception) { throw AssertionError("FIXTURE: $label", error) }

    private suspend fun expectVerification(block: suspend () -> Unit) {
        try {
            block()
            fail("R7 NATIVE ARCHIVE PRODUCT: mismatched archive must be rejected")
        } catch (error: UpdateFailure) {
            assertEquals("R7 NATIVE ARCHIVE PRODUCT: rejection reason", UpdateProblem.VERIFICATION, error.problem)
        }
    }
}
