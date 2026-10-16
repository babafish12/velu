package de.voci.legal

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LicenseEntry(
    val id: String,
    val title: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val kind: String,
    val components: List<String>,
) {
    val kindLabel: String get() = when (kind) {
        "project-license" -> "Eigene Lizenz"
        "license" -> "Lizenztext"
        else -> "Urheberrechtshinweise und Attributionen"
    }
}

@Serializable
private data class LicenseIndex(val schemaVersion: Int, val title: String, val entries: List<LicenseEntry>)

data class LicenseDocument(
    val title: String,
    val kindLabel: String,
    val components: List<String>,
    val text: String,
    val blocks: List<String>,
)

/** Reads only the bundled public package. No URL resolution, filesystem path supplied by
 * navigation, or private package lookup. Parsing, hashing and text preparation run on IO.
 */
class LicenseRepository(private val assets: AssetManager) {
    suspend fun entries(): List<LicenseEntry> = withContext(Dispatchers.IO) { readIndex() }

    suspend fun document(id: String): LicenseDocument = withContext(Dispatchers.IO) {
        require(ID.matches(id))
        val entry = readIndex().single { it.id == id }
        val bytes = assets.open("licenses/${entry.path}").use { it.readBytes() }
        require(bytes.size.toLong() == entry.sizeBytes && hash(bytes) == entry.sha256)
        document(entry.title, entry.kindLabel, entry.components, decode(bytes))
    }

    suspend fun notices(): LicenseDocument = withContext(Dispatchers.IO) {
        // Fixed public asset, deliberately separate from content-addressed text IDs.
        val bytes = assets.open("licenses/THIRD_PARTY_NOTICES.md").use { it.readBytes() }
        require(bytes.size == 91171 && hash(bytes) == NOTICES_HASH)
        document("Dritthinweise", "Vollständige Lizenz- und Urheberrechtshinweise", emptyList(), decode(bytes))
    }

    private fun readIndex(): List<LicenseEntry> {
        val bytes = assets.open("licenses/index.json").use { it.readBytes() }
        require(hash(bytes) == INDEX_HASH)
        val index = Json.decodeFromString<LicenseIndex>(decode(bytes))
        require(index.schemaVersion == 1 && index.title.isNotBlank() && index.entries.size == 164)
        val ids = mutableSetOf<String>()
        index.entries.forEach { entry ->
            require(ID.matches(entry.id) && ids.add(entry.id))
            require(entry.sha256 == entry.id && entry.path == "texts/${entry.id}.txt")
            require(entry.title.isNotBlank() && entry.sizeBytes > 0)
            require(entry.kind in setOf("project-license", "license", "attribution"))
            require(entry.components.isNotEmpty() && entry.components.all { it.isNotBlank() })
        }
        require(index.entries.count { it.kind == "project-license" } == 1)
        return index.entries.sortedWith(compareBy<LicenseEntry> { it.kind != "project-license" }.thenBy { it.title })
    }

    private fun document(title: String, kind: String, components: List<String>, text: String): LicenseDocument {
        require(text.isNotBlank())
        // Keep exact text for copying. Bounded native Text blocks avoid a single enormous
        // paragraph layout at large system font sizes; joining them reproduces every character.
        val blocks = buildList {
            var start = 0
            while (start < text.length) {
                val limit = minOf(start + 4000, text.length)
                var end = if (limit == text.length) limit else {
                    val newline = text.lastIndexOf('\n', limit - 1)
                    if (newline >= start) newline + 1 else limit
                }
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                add(text.substring(start, end))
                start = end
            }
        }
        return LicenseDocument(title, kind, components, text, blocks)
    }

    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        val ID = Regex("[a-f0-9]{64}")
        const val INDEX_HASH = "d07a596f651a22955bc0e9c3f10a240d4b8530254421f8c84259dc5d8c7180c8"
        const val NOTICES_HASH = "6f82f4245b970c31d80e90fc288742482737543a40cb7af29576f0c7131bcb5a"
    }
}
