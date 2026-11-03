package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SupabasePkceTest {
    @Test
    fun generatedVerifierMeetsPkceLengthAndChallengeUsesS256() {
        val (verifier, request) = SupabasePkce.createRequest()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(43, request.codeChallenge.length)
        assertTrue(request.codeChallenge.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SupabasePkce.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun codeExchangeUsesSupabasePkceGrantAndRequiredBodyFields() {
        val body = SupabasePkce.tokenExchangeBody("one-time-code", "saved-verifier")
        assertEquals("/auth/v1/token?grant_type=pkce", SupabasePkce.TOKEN_EXCHANGE_PATH)
        assertEquals("one-time-code", body.getString("auth_code"))
        assertEquals("saved-verifier", body.getString("code_verifier"))
        assertEquals(2, JSONObject(body.toString()).length())
    }

    @Test
    fun emailConfirmationAndRecoveryRequestsCarryS256Challenge() {
        val request = PkceRequest("computed-challenge")
        val signup = SupabasePkce.signupBody("test@example.invalid", "temporary-test-password", request)
        val recovery = SupabasePkce.recoveryBody("test@example.invalid", request)

        assertEquals("computed-challenge", signup.getString("code_challenge"))
        assertEquals("s256", signup.getString("code_challenge_method"))
        assertEquals("computed-challenge", recovery.getString("code_challenge"))
        assertEquals("s256", recovery.getString("code_challenge_method"))
        assertFalse(recovery.has("password"))
    }

    @Test
    fun encryptedVerifierPersistsAcrossStoreInstancesAndIsConsumedOnce() {
        val persistence = MemoryPersistence()
        val verifier = "v".repeat(43)
        val firstStore = store(persistence) { verifier to PkceRequest("challenge") }
        firstStore.begin(PendingAuthKind.PASSWORD_RECOVERY)

        assertTrue(persistence.value.orEmpty().startsWith("encrypted:"))
        val restored = store(persistence).consume()
        assertEquals(PendingAuthKind.PASSWORD_RECOVERY, restored?.kind)
        assertEquals(verifier, restored?.verifier)
        assertNull(store(persistence).consume())
        assertNull(persistence.value)
    }

    @Test
    fun failedPersistencePreventsTheAuthFlowFromStarting() {
        val persistence = MemoryPersistence().apply { allowWrite = false }
        assertThrows(PkceAuthException::class.java) {
            store(persistence) { "v".repeat(43) to PkceRequest("challenge") }
                .begin(PendingAuthKind.EMAIL_CONFIRMATION)
        }
        assertNull(persistence.value)
    }

    @Test
    fun callbackAcceptsOnlyExpectedSchemeHostAndEmptyPathWithQueryCode() {
        assertTrue(SupabaseCallbackParser.parse("appinternetcontrol://auth-callback?code=one-time") is PkceCallback.Code)
        listOf(
            "otherapp://auth-callback?code=x",
            "?code=x",
            "appinternetcontrol://other-host?code=x",
            "appinternetcontrol://auth-callback/wrong?code=x",
            "appinternetcontrol://auth-callback/#code=x",
            "appinternetcontrol://auth-callback?access_token=token&refresh_token=token",
            "appinternetcontrol://auth-callback?code=one&code=two",
            "appinternetcontrol://auth-callback?code=%ZZ",
            "appinternetcontrol://auth-callback",
        ).forEach { assertTrue(SupabaseCallbackParser.parse(it) is PkceCallback.Invalid) }
    }

    @Test
    fun providerErrorsAreSanitizedAndClearPendingState() {
        val persistence = MemoryPersistence()
        val pending = store(persistence) { "v".repeat(43) to PkceRequest("challenge") }
        pending.begin(PendingAuthKind.EMAIL_CONFIRMATION)

        val error = assertThrows(PkceAuthException::class.java) {
            SupabasePkceCallbackFlow.complete(
                "appinternetcontrol://auth-callback?error=access_denied&error_description=private-detail",
                pending,
            ) { _, _ -> error("exchange must not run") }
        }
        assertEquals("The authentication link was declined or expired. Please start again.", error.message)
        assertNull(persistence.value)
    }

    @Test
    fun successfulCodeExchangeUsesSavedVerifierAndClearsPendingState() {
        val persistence = MemoryPersistence()
        val verifier = "v".repeat(43)
        val pending = store(persistence) { verifier to PkceRequest("challenge") }
        pending.begin(PendingAuthKind.PASSWORD_RECOVERY)
        var exchangedCode: String? = null
        var exchangedVerifier: String? = null

        val kind = SupabasePkceCallbackFlow.complete(
            "appinternetcontrol://auth-callback?code=one-time-code",
            pending,
        ) { code, savedVerifier ->
            exchangedCode = code
            exchangedVerifier = savedVerifier
        }

        assertEquals(PendingAuthKind.PASSWORD_RECOVERY, kind)
        assertEquals("one-time-code", exchangedCode)
        assertEquals(verifier, exchangedVerifier)
        assertNull(persistence.value)
    }

    @Test
    fun exchangeFailureClearsVerifierAndDoesNotExposeCodeOrVerifier() {
        val persistence = MemoryPersistence()
        val verifier = "private-verifier-value-123456789012345678901234567890123456"
        val pending = store(persistence) { verifier to PkceRequest("challenge") }
        pending.begin(PendingAuthKind.EMAIL_CONFIRMATION)
        val code = "private-one-time-code"

        val error = assertThrows(PkceAuthException::class.java) {
            SupabasePkceCallbackFlow.complete("appinternetcontrol://auth-callback?code=$code", pending) { _, _ ->
                throw IllegalStateException("contains backend detail")
            }
        }
        assertFalse(error.message.orEmpty().contains(code))
        assertFalse(error.message.orEmpty().contains(verifier))
        assertNull(persistence.value)
    }

    @Test
    fun invalidCallbackDoesNotConsumeAValidPendingFlow() {
        val persistence = MemoryPersistence()
        val pending = store(persistence) { "v".repeat(43) to PkceRequest("challenge") }
        pending.begin(PendingAuthKind.EMAIL_CONFIRMATION)

        assertThrows(PkceAuthException::class.java) {
            SupabasePkceCallbackFlow.complete("evil://auth-callback?code=attacker-code", pending) { _, _ ->
                error("exchange must not run")
            }
        }
        assertTrue(persistence.value != null)
    }

    private fun store(
        persistence: MemoryPersistence,
        createRequest: () -> Pair<String, PkceRequest> = SupabasePkce::createRequest,
    ) = PendingPkceAuthStore(
        persistence = persistence,
        encrypt = { "encrypted:$it" },
        decrypt = { it.removePrefix("encrypted:") },
        createRequest = createRequest,
    )

    private class MemoryPersistence : PkceAuthPersistence {
        var value: String? = null
        var allowWrite = true
        override fun readEncrypted() = value
        override fun writeEncrypted(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
        override fun clear(): Boolean {
            value = null
            return true
        }
    }
}
