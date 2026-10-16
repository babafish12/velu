package de.voci

import androidx.datastore.preferences.core.*
import de.voci.data.PreferencesStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class R7ContentPreferenceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun contentCountReopensDurablyAndLegacyMinutesAndUnknownFieldsSurvive() = runBlocking {
        for ((minutes, mapped) in listOf(1 to 5, 3 to 10, 5 to 20)) {
            val file = temp.newFolder().resolve("settings.preferences_pb")
            var owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var raw = PreferenceDataStoreFactory.create(scope = owner) { file }
            val original = preferencesOf(intPreferencesKey("minutes") to minutes,
                stringPreferencesKey("future") to "unchanged", stringPreferencesKey("provider") to "unknown",
                booleanPreferencesKey("wheelTranslationFirst") to true)
            try {
                raw.updateData { original }
                assertEquals(mapped, PreferencesStore(raw).flow.first().entryCount)
                assertEquals(original, raw.data.first())
                for (count in listOf(20, 5, 10)) {
                    PreferencesStore(raw).entryCount(count)
                    owner.coroutineContext[Job]!!.cancelAndJoin()
                    owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    raw = PreferenceDataStoreFactory.create(scope = owner) { file }
                    val p = PreferencesStore(raw).flow.first()
                    assertEquals(count, p.entryCount); assertEquals(minutes, p.minutes)
                    assertEquals("unknown", p.provider); assertTrue(p.wheelTranslationFirst)
                    assertEquals(original.asMap(), raw.data.first().asMap().filterKeys { it.name != "entryCount" })
                }
            } finally { owner.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
}
