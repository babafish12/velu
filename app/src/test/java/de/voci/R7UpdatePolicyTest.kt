package de.voci

import de.voci.update.ReleasePolicy
import org.junit.Assert.*
import org.junit.Test

/** Synthetic metadata policy tests only. The proposed repository and release below are not
 * verified live resources; these tests prove no GitHub call, APK download or installer flow.
 */
class R7UpdatePolicyTest {
    @Test fun validNewerStableReleaseOffersExactlyBoundCandidate() {
        val candidate = ReleasePolicy.candidate(releaseJson, manifestJson, installedVersionCode = 2, sdkInt = 36)
        assertNotNull("R7 UPDATE PRODUCT: a valid newer stable release must be offered", candidate)
        val offered = requireNotNull(candidate)
        assertEquals("https://github.com/babafish12/velu/releases/download/v0.3.0/velu.apk", offered.apkUrl)
        assertEquals(3, offered.versionCode)
        assertEquals("0.3.0", offered.versionName)
        assertEquals("de.voci", offered.applicationId)
        assertEquals(28, offered.minSdk)
        assertEquals(12_345_678L, offered.sizeBytes)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", offered.sha256)
        assertEquals("v0.3.0", offered.releaseTag)
    }

    @Test fun equalVersionDoesNotOfferUpdate() {
        assertNull("R7 UPDATE CONTROL: equal version must not be offered",
            ReleasePolicy.candidate(releaseJson, manifestJson, installedVersionCode = 3, sdkInt = 36))
    }

    private val manifestJson = """
        {"schemaVersion":1,"applicationId":"de.voci","versionCode":3,"versionName":"0.3.0","apkName":"velu.apk","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","sizeBytes":12345678,"minSdk":28}
    """.trimIndent()

    private val releaseJson = """
        {
          "tag_name": "v0.3.0",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "velu.apk",
              "size": 12345678,
              "browser_download_url": "https://github.com/babafish12/velu/releases/download/v0.3.0/velu.apk"
            },
            {
              "name": "velu-update.json",
              "size": ${manifestJson.toByteArray(Charsets.UTF_8).size},
              "browser_download_url": "https://github.com/babafish12/velu/releases/download/v0.3.0/velu-update.json"
            }
          ]
        }
    """.trimIndent()
}
