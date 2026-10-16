package de.voci.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Reject duplicate decoded object keys and bound nesting before kotlinx parses unknown fields.
 * Syntax and value typing still belong to the strict JSON parser and ReleasePolicy.
 */
internal class MetadataJson(private val text: String) {
    private var offset = 0
    fun check() { value(0); whitespace(); requireUpdate(offset == text.length) }
    private fun whitespace() { while (offset < text.length && text[offset] in " \r\n\t") offset++ }
    private fun take(char: Char): Boolean {
        whitespace()
        if (offset < text.length && text[offset] == char) { offset++; return true }
        return false
    }
    private fun string(): String {
        whitespace()
        val start = offset
        requireUpdate(offset < text.length && text[offset++] == '"')
        var escaped = false
        while (offset < text.length) {
            val c = text[offset++]
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') return Json.parseToJsonElement(text.substring(start, offset)).jsonPrimitive.content
        }
        throw UpdateFailure(UpdateProblem.INVALID)
    }
    private fun value(depth: Int) {
        requireUpdate(depth <= 16)
        whitespace()
        requireUpdate(offset < text.length)
        when (text[offset]) {
            '{' -> {
                offset++
                val keys = mutableSetOf<String>()
                if (take('}')) return
                do {
                    requireUpdate(keys.add(string()))
                    requireUpdate(take(':'))
                    value(depth + 1)
                } while (take(','))
                requireUpdate(take('}'))
            }
            '[' -> {
                offset++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                requireUpdate(take(']'))
            }
            '"' -> string()
            else -> {
                val start = offset
                while (offset < text.length && text[offset] !in ",]} \r\n\t") offset++
                requireUpdate(offset > start)
            }
        }
    }
}
