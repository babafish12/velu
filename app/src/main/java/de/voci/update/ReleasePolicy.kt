package de.voci.update

import kotlinx.serialization.json.*

/** Exact, same-release binding. The archive verifier separately authenticates installed signers. */
data class ReleaseCandidate(
    val apkUrl: String,
    val versionCode: Int,
    val versionName: String,
    val applicationId: String,
    val minSdk: Int,
    val sizeBytes: Long,
    val sha256: String,
    val releaseTag: String
)

enum class UpdateProblem { NO_RELEASE, OFFLINE, QUOTA, SERVER, INVALID, SPACE, VERIFICATION, PERMISSION, PLATFORM }
class UpdateFailure(val problem: UpdateProblem) : Exception(problem.name)
internal fun requireUpdate(value: Boolean, problem: UpdateProblem = UpdateProblem.INVALID) {
    if (!value) throw UpdateFailure(problem)
}

data class ReleaseAsset(val name: String, val size: Long, val url: String)
data class StableRelease(val tag: String, val stable: Boolean, val assets: List<ReleaseAsset>) {
    fun manifest(): ReleaseAsset = assets.singleOrNull { it.name == "velu-update.json" }
        ?: throw UpdateFailure(UpdateProblem.INVALID)
}

object ReleasePolicy {
    // Proposed target, pending Parent's repository creation/publication verification.
    const val REPOSITORY = "babafish12/velu"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val RELEASE_LIMIT = 256 * 1024
    const val MANIFEST_LIMIT = 16 * 1024
    const val APK_LIMIT = 128L * 1024 * 1024
    private val version = Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")
    private val assetName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")

    fun release(releaseJson: String): StableRelease = parsed {
        val r = objectJson(releaseJson, RELEASE_LIMIT)
        val tag = r.string("tag_name")
        requireUpdate(tag.startsWith("v") && version.matches(tag.drop(1)))
        val stable = !r.bool("draft") && !r.bool("prerelease")
        // Read both flags even for drafts: malformed flags never masquerade as no release.
        r.bool("prerelease")
        val list = r["assets"] as? JsonArray ?: throw UpdateFailure(UpdateProblem.INVALID)
        requireUpdate(list.size in 1..32)
        val assets = list.map { element ->
            val a = element as? JsonObject ?: throw UpdateFailure(UpdateProblem.INVALID)
            val name = a.string("name")
            requireUpdate(assetName.matches(name) && !name.contains(".."))
            val size = a.integer("size", APK_LIMIT)
            val url = a.string("browser_download_url")
            UpdateUrls.requireAsset(url, tag, name)
            ReleaseAsset(name, size, url)
        }
        requireUpdate(assets.map { it.name }.toSet().size == assets.size)
        r["html_url"]?.let { requireUpdate(r.string("html_url") == "https://github.com/$REPOSITORY/releases/tag/$tag") }
        StableRelease(tag, stable, assets)
    }

    fun candidate(releaseJson: String, manifestJson: String, installedVersionCode: Int, sdkInt: Int): ReleaseCandidate? =
        candidate(releaseJson, manifestJson, installedVersionCode.toLong(), sdkInt)

    fun candidate(releaseJson: String, manifestJson: String, installedVersionCode: Long, sdkInt: Int): ReleaseCandidate? = parsed {
        val r = release(releaseJson)
        if (!r.stable) return@parsed null
        val m = objectJson(manifestJson, MANIFEST_LIMIT)
        requireUpdate(m.keys == setOf("schemaVersion", "applicationId", "versionCode", "versionName", "apkName", "sha256", "sizeBytes", "minSdk"))
        requireUpdate(m.integer("schemaVersion", 1) == 1L)
        requireUpdate(m.string("applicationId") == "de.voci")
        val code = m.integer("versionCode", Int.MAX_VALUE.toLong()).toInt()
        val name = m.string("versionName")
        requireUpdate(version.matches(name) && r.tag == "v$name")
        val apkName = m.string("apkName")
        requireUpdate(assetName.matches(apkName) && apkName.endsWith(".apk") && !apkName.contains(".."))
        val hash = m.string("sha256")
        requireUpdate(Regex("[0-9a-f]{64}").matches(hash))
        val size = m.integer("sizeBytes", APK_LIMIT)
        val minSdk = m.integer("minSdk", 1000).toInt()
        requireUpdate(minSdk >= 28 && minSdk <= sdkInt)
        val apk = r.assets.singleOrNull { it.name == apkName } ?: throw UpdateFailure(UpdateProblem.INVALID)
        requireUpdate(apk.size == size)
        val manifest = r.manifest()
        requireUpdate(manifest.size <= MANIFEST_LIMIT && manifest.size == manifestJson.toByteArray(Charsets.UTF_8).size.toLong())
        requireUpdate(installedVersionCode >= 0)
        if (code.toLong() <= installedVersionCode) null else
            ReleaseCandidate(apk.url, code, name, "de.voci", minSdk, size, hash, r.tag)
    }

    private fun objectJson(text: String, limit: Int): JsonObject {
        requireUpdate(text.toByteArray(Charsets.UTF_8).size in 1..limit)
        MetadataJson(text).check()
        return Json.parseToJsonElement(text) as? JsonObject ?: throw UpdateFailure(UpdateProblem.INVALID)
    }
    private fun JsonObject.string(key: String): String {
        val v = this[key] as? JsonPrimitive ?: throw UpdateFailure(UpdateProblem.INVALID)
        requireUpdate(v.isString && v.content.length in 1..2048)
        return v.content
    }
    private fun JsonObject.bool(key: String): Boolean {
        val v = this[key] as? JsonPrimitive ?: throw UpdateFailure(UpdateProblem.INVALID)
        requireUpdate(!v.isString && v.content in setOf("true", "false"))
        return v.content == "true"
    }
    private fun JsonObject.integer(key: String, max: Long): Long {
        val v = this[key] as? JsonPrimitive ?: throw UpdateFailure(UpdateProblem.INVALID)
        requireUpdate(!v.isString && Regex("[1-9][0-9]{0,18}").matches(v.content))
        val n = v.content.toLongOrNull() ?: throw UpdateFailure(UpdateProblem.INVALID)
        requireUpdate(n in 1..max)
        return n
    }
    private inline fun <T> parsed(block: () -> T): T = try { block() }
    catch (e: UpdateFailure) { throw e }
    catch (_: IllegalArgumentException) { throw UpdateFailure(UpdateProblem.INVALID) }
}
