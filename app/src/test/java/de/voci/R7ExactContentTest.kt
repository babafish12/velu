package de.voci

import de.voci.ai.*
import de.voci.auth.*
import de.voci.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic, in-memory transport only. Exercises production request, SSE and parser; no login claim. */
class R7ExactContentTest {
    private fun lesson(n: Int) = LessonContent("fixture", "Restaurant", "it-IT",
        (1..n).map { VocabItem("v$it", "Frase $it", "Bedeutung $it", "Esempio $it", "Beispiel $it") },
        listOf(DialogueTurn("d1", 0, "Uno", "Eins"), DialogueTurn("d2", 1, "Due", "Zwei")),
        listOf(RecognitionQuestion("q1", "v1", "Uno?", listOf("Eins", "Zwei"), 0),
            RecognitionQuestion("q2", "v2", "Due?", listOf("Zwei", "Drei"), 0)))

    @Test fun exactFiveTenTwentyUseProductionRequestAndParser() = runTest {
        for (count in listOf(5, 10, 20)) {
            val expected = lesson(count)
            val spec = SessionSpec("Restaurant", recall = true, entryCount = count)
            val paths = mutableListOf<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                val body = when (request.url.encodedPath) {
                    "/v1/models" -> """{"models":[{"slug":"fixture","display_name":"Fixture","visibility":"list"}]}"""
                    "/v1/responses" -> {
                        val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
                        val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                        val prompt = json.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
                        assertEquals(LessonPrompt.text(spec), prompt)
                        assertTrue("exact selected count in real request", prompt.contains("EXACTLY $count distinct"))
                        assertFalse(json.getValue("store").jsonPrimitive.boolean)
                        val text = Json.encodeToString(expected)
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":${Json.encodeToString(text)}}\n\n" +
                            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                    }
                    else -> throw AssertionError("Unexpected request, never proceed to network")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("synthetic").body(body.toResponseBody()).build()
            }.build()
            val vault = object : CredentialStore {
                var value = VaultState(accounts = listOf(Registration("oaiapp_fixture", "fixture", "fixture",
                    "synthetic-access", "synthetic-refresh", scopes = setOf("resource.invoke", "chatgpt.tokens.use.direct"),
                    expiresAt = Long.MAX_VALUE)), active = "oaiapp_fixture")
                override fun read() = value
                override fun write(state: VaultState) { value = state }
            }
            try {
                val actual = ChatGptProvider(http, AuthManager(null, http, backgroundScope, vault)).generate(spec)
                assertEquals(expected.items, actual.items)
                assertEquals(expected.dialogue, actual.dialogue)
                assertEquals(expected.questions, actual.questions)
                assertEquals(listOf("/v1/models", "/v1/responses"), paths)
            } finally { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun exactCountsRejectBothShortAndLongResponses() {
        for (count in listOf(5, 10, 20)) for (actual in listOf(count - 1, count + 1)) {
            assertTrue("$actual must not satisfy $count", runCatching {
                LessonPrompt.parse(Json.encodeToString(lesson(actual)), SessionSpec("Restaurant", entryCount = count))
            }.isFailure)
        }
    }

    @Test fun twentyIsMaximumAndMalformedContentStillFailsClosed() {
        val valid = lesson(20)
        assertEquals(20, LessonValidator.validate(valid).items.size)
        val first = valid.items.first()
        val invalid = listOf(lesson(21),
            valid.copy(items = valid.items.dropLast(1) + first.copy(id = "different")),
            valid.copy(items = valid.items.dropLast(1) + first.copy(target = "Different")),
            valid.copy(items = valid.items.dropLast(1) + first.copy(id = "last", target = "\u0001")),
            valid.copy(language = "invalid"),
            valid.copy(questions = listOf(valid.questions.first().copy(answer = 4))),
            valid.copy(questions = listOf(valid.questions.first().copy(itemId = "missing"))))
        for (candidate in invalid) assertTrue(runCatching {
            LessonPrompt.parse(Json.encodeToString(candidate), SessionSpec("Restaurant", entryCount = 20))
        }.isFailure)
    }

    @Test fun legacySerializedSpecRemainsReadableWithoutAnInventedCount() {
        val old = Json.decodeFromString<SessionSpec>("""{"topic":"Restaurant","language":"it-IT","minutes":3,"recall":false}""")
        assertNull(old.entryCount)
        assertEquals(3, old.minutes)
        assertEquals(7, LessonPrompt.parse(Json.encodeToString(lesson(7)), old).items.size)
    }
}
