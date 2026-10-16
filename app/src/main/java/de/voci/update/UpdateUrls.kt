package de.voci.update

import java.net.URI

object UpdateUrls {
    private fun uri(value: String): URI {
        requireUpdate(value.length in 1..4096)
        val u = try { URI(value) } catch (_: Exception) { throw UpdateFailure(UpdateProblem.INVALID) }
        requireUpdate(u.scheme == "https" && u.rawUserInfo == null && u.rawFragment == null &&
            (u.port == -1 || u.port == 443) && u.host != null && u.rawPath == u.normalize().rawPath &&
            !u.rawPath.contains('%') && !u.rawPath.contains('\\'))
        return u
    }
    fun requireAsset(value: String, tag: String, name: String) {
        uri(value)
        requireUpdate(value == "https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/$tag/$name")
    }
    fun requireInitial(value: String) {
        if (value == ReleasePolicy.LATEST_URL) { uri(value); return }
        val u = uri(value)
        requireUpdate(u.host == "github.com" && u.rawQuery == null &&
            Regex("/" + Regex.escape(ReleasePolicy.REPOSITORY) + "/releases/download/v[0-9.]+/[A-Za-z0-9][A-Za-z0-9._-]{0,95}").matches(u.rawPath))
        requireUpdate(!u.rawPath.contains(".."))
    }
    fun requireRedirect(value: String) {
        val u = uri(value)
        // GitHub release delivery only, not arbitrary GitHub user content or repository files.
        requireUpdate(u.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
        requireUpdate(Regex("/github-production-release-asset(?:-2e65be)?/[0-9]{1,20}/[A-Za-z0-9-]{1,128}").matches(u.rawPath))
    }
}
