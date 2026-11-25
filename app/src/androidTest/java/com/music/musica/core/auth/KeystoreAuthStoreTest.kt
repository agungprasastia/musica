package com.music.musica.core.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.musica.core.model.AccountProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KeystoreAuthStoreTest {

    private lateinit var baseContext: Context
    private lateinit var isolatedContext: Context
    private lateinit var testPrefsName: String
    private lateinit var authStore: KeystoreAuthStore

    @Before
    fun setUp() {
        baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        testPrefsName = "musica_secure_auth_test_${UUID.randomUUID()}"
        isolatedContext = IsolatedPrefsContext(baseContext, testPrefsName)
        authStore = KeystoreAuthStore(isolatedContext)
    }

    @After
    fun tearDown() {
        authStore.clearSession()
        baseContext.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        baseContext.deleteSharedPreferences(testPrefsName)
    }

    @Test
    fun initialEmptyStore_returnsNullSessionAndNotSignedIn() {
        assertNull(authStore.session.value)
        assertNull(authStore.currentCookie)
        assertFalse(authStore.isSignedIn)
        assertNull(authStore.getVisitorData())
    }

    @Test
    fun saveSession_encryptsWithAndroidKeystoreAndReadsBackOnNewInstance() {
        val expected = GoogleSession(
            cookie = "SAPISID=test_sapisid_token; SID=test_sid_token",
            visitorData = "Cgt0ZXN0X3Zpc2l0b3I%3D",
            dataSyncId = "1234567890||",
            profile = AccountProfile(
                name = "Musica Listener",
                handle = "@musicalistener",
                email = "listener@example.invalid",
                avatarUrl = null,
            ),
        )

        authStore.saveSession(expected)

        assertEquals(expected, authStore.session.value)
        assertEquals(expected.cookie, authStore.currentCookie)
        assertTrue(authStore.isSignedIn)
        assertEquals(expected.visitorData, authStore.getVisitorData())

        // Verify stored preference value is encrypted and does not leak plaintext cookie
        val rawStored = baseContext.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .getString("encrypted_session", null)
        assertNotNull(rawStored)
        assertFalse(rawStored!!.contains("test_sapisid_token"))

        // Re-open store against the same isolated prefs to verify Android Keystore decryption
        val reloaded = KeystoreAuthStore(isolatedContext)
        assertEquals(expected, reloaded.session.value)
        assertEquals(expected.cookie, reloaded.currentCookie)
        assertTrue(reloaded.isSignedIn)
        assertEquals(expected.profile, reloaded.session.value?.profile)
    }

    @Test
    fun updateProfile_persistsProfileWhenSessionExistsAndNoOpsWhenEmpty() {
        val profile = AccountProfile(name = "Updated User", handle = "@updated")

        // No active session -> stays empty
        authStore.updateProfile(profile)
        assertNull(authStore.session.value)

        // With active session -> updates and persists encrypted session
        val session = GoogleSession(cookie = "SAPISID=abc123; HSID=xyz")
        authStore.saveSession(session)
        authStore.updateProfile(profile)

        val reloaded = KeystoreAuthStore(isolatedContext)
        assertEquals(profile, reloaded.session.value?.profile)
        assertEquals(session.cookie, reloaded.currentCookie)
    }

    @Test
    fun saveVisitorData_usedAsFallbackWhenSessionHasNoVisitorData() {
        authStore.saveVisitorData("guest_visitor_123")
        assertEquals("guest_visitor_123", authStore.getVisitorData())

        authStore.saveSession(
            GoogleSession(
                cookie = "SAPISID=token_1",
                visitorData = "session_visitor_456",
            ),
        )
        assertEquals("session_visitor_456", authStore.getVisitorData())

        authStore.clearSession()
        assertEquals("guest_visitor_123", authStore.getVisitorData())
    }

    @Test
    fun clearSession_removesPersistedSessionAndResetsState() {
        authStore.saveSession(
            GoogleSession(
                cookie = "SAPISID=secret_token; SID=sid_value",
                visitorData = "session_visitor",
            ),
        )
        assertTrue(authStore.isSignedIn)

        authStore.clearSession()

        assertNull(authStore.session.value)
        assertNull(authStore.currentCookie)
        assertFalse(authStore.isSignedIn)

        val reloaded = KeystoreAuthStore(isolatedContext)
        assertNull(reloaded.session.value)
        assertNull(reloaded.currentCookie)
        assertFalse(reloaded.isSignedIn)
    }

    private class IsolatedPrefsContext(
        base: Context,
        private val prefsName: String,
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefsName, mode)
    }
}
