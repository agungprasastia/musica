package com.music.musica.core.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.musica.core.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Verifies real protobuf DataStore disk persistence on Android using isolated temp files.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreInstrumentedTest {

    private lateinit var tempFile: File
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tempFile = File(context.cacheDir, "settings_inst_${UUID.randomUUID()}.preferences_pb")
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        tempFile.delete()
    }

    @Test
    fun writesPersistAcrossRealProtobufDataStore() = runBlocking {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFile },
        )
        val store = SettingsStore(dataStore)

        store.setThemeMode(ThemeMode.DARK)
        store.setAudioCacheLimitMb(1024)
        store.setSleepTimerFadeOut(false)

        val read = store.settings.first()
        assertEquals(ThemeMode.DARK, read.themeMode)
        assertEquals(1024, read.audioCacheLimitMb)
        assertFalse(read.sleepTimerFadeOut)
        assertTrue(tempFile.exists())
    }
}
