package de.voci

import de.voci.update.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic release metadata only; no claim that this repository/release exists. */
internal object R7UpdateFixtures {
    val hash = "01".repeat(32)
    fun manifest(code: String = "3", size: Long = 4, hash: String = this.hash) =
        """{"schemaVersion":1,"applicationId":"de.voci","versionCode":$code,"versionName":"0.3.0","apkName":"velu.apk","sha256":"$hash","sizeBytes":$size,"minSdk":28}"""
    fun release(manifest: String = manifest(), size: Long = 4) = """{"tag_name":"v0.3.0","draft":false,"prerelease":false,"assets":[{"name":"velu.apk","size":$size,"browser_download_url":"https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/v0.3.0/velu.apk"},{"name":"velu-update.json","size":${manifest.toByteArray().size},"browser_download_url":"https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/v0.3.0/velu-update.json"}]}"""
    fun candidate(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): ReleaseCandidate {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val m = manifest(size = bytes.size.toLong(), hash = hash)
        return requireNotNull(ReleasePolicy.candidate(release(m, bytes.size.toLong()), m, 2L, 36))
    }
}

class R7UpdateMetadataTest {
    private fun invalid(m: String, r: String = R7UpdateFixtures.release(m)) {
        assertThrows(UpdateFailure::class.java) { ReleasePolicy.candidate(r, m, 2L, 36) }
    }
    @Test fun newerEqualOlderAndLongInstalledCode() {
        val m = R7UpdateFixtures.manifest(); val r = R7UpdateFixtures.release(m)
        assertEquals(3, ReleasePolicy.candidate(r, m, 2L, 36)!!.versionCode)
        assertNull(ReleasePolicy.candidate(r, m, 3L, 36))
        assertNull(ReleasePolicy.candidate(r, m, 4L, 36))
        assertNull(ReleasePolicy.candidate(r, m, Long.MAX_VALUE, 36))
    }
    @Test fun draftAndPrereleaseNeverOffer() {
        val m = R7UpdateFixtures.manifest(); val r = R7UpdateFixtures.release(m)
        assertNull(ReleasePolicy.candidate(r.replace("\"draft\":false", "\"draft\":true"), m, 2L, 36))
        assertNull(ReleasePolicy.candidate(r.replace("\"prerelease\":false", "\"prerelease\":true"), m, 2L, 36))
    }
    @Test fun schemaPackageIntegerOverflowHashAndNameBounds() {
        val m = R7UpdateFixtures.manifest()
        for (bad in listOf("0", "-1", "3.0", "3e0", "\"3\"", "2147483648", "9223372036854775808")) invalid(R7UpdateFixtures.manifest(bad))
        for ((from, to) in listOf("\"schemaVersion\":1" to "\"schemaVersion\":2", "de.voci" to "de.other",
            R7UpdateFixtures.hash to "G".repeat(64), "velu.apk" to "../velu.apk", "0.3.0" to "x".repeat(100),
            "\"minSdk\":28" to "\"minSdk\":37", "\"sizeBytes\":4" to "\"sizeBytes\":134217729")) invalid(m.replace(from, to))
    }
    @Test fun duplicateMissingAndInconsistentAssetsAreInvalid() {
        val m = R7UpdateFixtures.manifest(); val r = R7UpdateFixtures.release(m)
        invalid(m, r.replace("\"name\":\"velu-update.json\"", "\"name\":\"velu.apk\""))
        val duplicate = """{"name":"velu.apk","size":4,"browser_download_url":"https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/v0.3.0/velu.apk"}"""
        invalid(m, r.dropLast(2) + ",$duplicate]}")
        invalid(m, r.replace("velu.apk", "other.apk"))
        invalid(m, r.replace("\"size\":4,", "\"size\":5,"))
        invalid(m, r.replace("/v0.3.0/", "/v0.4.0/"))
        invalid(m, r.replace("github.com/", "evil.example/"))
        invalid(m, r.replace("\"tag_name\":\"v0.3.0\"", "\"tag_name\":\"v0.4.0\""))
        invalid(m + " ", r)
    }
    @Test fun malformedAndOversizedMetadataCannotBecomeCurrent() {
        invalid("{")
        invalid(" ".repeat(16 * 1024 + 1))
        invalid(R7UpdateFixtures.manifest(), " ".repeat(256 * 1024 + 1))
        invalid(R7UpdateFixtures.manifest(), "{}")
    }
    @Test fun duplicateKeysStrictSizesFlagsAndMissingManifestAreInvalid() {
        val m = R7UpdateFixtures.manifest(); val r = R7UpdateFixtures.release(m)
        invalid(m.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))
        for (value in listOf("4.0", "4e0", "\"4\"", "0", "-1", "9223372036854775808")) {
            invalid(m.replace("\"sizeBytes\":4", "\"sizeBytes\":$value"))
            invalid(m, r.replace("\"size\":4,", "\"size\":$value,"))
        }
        invalid(m, r.replace("\"draft\":false", "\"draft\":\"false\""))
        invalid(m, r.replace("velu-update.json", "missing.json"))
        invalid(m, r.replace("\"assets\":[", "\"assets\":[{} ,"))
        invalid(m.replace("\"minSdk\":28", "\"minSdk\":28.0"))
        invalid(m, r.replace("\"tag_name\":\"v0.3.0\"", "\"tag_name\":\"../../x\""))
    }
    @Test fun urlAllowlistIsExactAndRedirectsAreNarrow() {
        val initial = R7UpdateFixtures.candidate().apkUrl
        UpdateUrls.requireAsset(initial, "v0.3.0", "velu.apk")
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc-123?sig=synthetic"
        UpdateUrls.requireRedirect(cdn)
        for (bad in listOf(cdn.replace("https:", "http:"), cdn.replace("https://", "https://user@"),
            cdn.replace(".com/", ".com:444/"), cdn + "#fragment", cdn.replace("/123/", "/123/../"),
            cdn.replace("/123/", "/%31%32%33/"))) {
            assertThrows(UpdateFailure::class.java) { UpdateUrls.requireRedirect(bad) }
        }
        for (url in listOf("http://github.com/x", "https://github.com:444/x", "https://user@github.com/x",
            initial + "#fragment", "https://raw.githubusercontent.com/x", "https://evil.githubusercontent.com/x",
            "https://release-assets.githubusercontent.com/unrelated/x", "https://github.com/other/repo/releases/download/v0.3.0/velu.apk")) {
            assertThrows(UpdateFailure::class.java) { UpdateUrls.requireRedirect(url) }
        }
    }
}
