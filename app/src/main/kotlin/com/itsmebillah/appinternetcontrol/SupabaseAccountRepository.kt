package com.itsmebillah.appinternetcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supabase Auth/PostgREST client using only the public publishable key. */
class SupabaseAccountRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)
    private val sessionCipher = AndroidSessionCipher()
    private val pendingPkce = PendingPkceAuthStore(
        persistence = object : PkceAuthPersistence {
            override fun readEncrypted(): String? = prefs.getString(PKCE_STATE_KEY, null)
            override fun writeEncrypted(value: String): Boolean =
                prefs.edit().putString(PKCE_STATE_KEY, value).commit()
            override fun clear(): Boolean = prefs.edit().remove(PKCE_STATE_KEY).commit()
        },
        encrypt = sessionCipher::encrypt,
        decrypt = sessionCipher::decrypt,
    )

    val isConfigured: Boolean
        get() = runCatching {
            URL(BuildConfig.SUPABASE_URL).protocol == "https" &&
                BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() &&
                !BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("YOUR_")
        }.getOrDefault(false)

    fun currentEmail(): String? = session()?.optJSONObject("user")?.optString("email")
        ?.takeIf { !it.isNullOrBlank() && it != "null" }

    fun currentUserId(): String? = session()?.optJSONObject("user")?.optString("id")
        ?.takeIf { !it.isNullOrBlank() && it != "null" }

    fun signUp(email: String, password: String): String {
        val pkce = pendingPkce.begin(PendingAuthKind.EMAIL_CONFIRMATION)
        val result = try {
            request("POST", "/auth/v1/signup?redirect_to=${encode(AUTH_REDIRECT)}",
                SupabasePkce.signupBody(email.trim(), password, pkce))
        } catch (failure: Exception) {
            runCatching { pendingPkce.clear() }
            throw failure
        }
        if (result.has("access_token")) {
            pendingPkce.clear()
            saveSession(result)
        }
        return if (result.has("access_token")) "Account created and signed in."
        else "Sign-up submitted. Confirm your email before signing in if email confirmation is enabled."
    }

    fun signIn(email: String, password: String) {
        val result = request("POST", "/auth/v1/token?grant_type=password", JSONObject()
            .put("email", email.trim()).put("password", password))
        check(result.has("access_token")) { "Sign-in did not return an active session. Check email confirmation and try again." }
        pendingPkce.clear()
        saveSession(result)
    }

    fun requestPasswordRecovery(email: String) {
        val pkce = pendingPkce.begin(PendingAuthKind.PASSWORD_RECOVERY)
        try {
            request("POST", "/auth/v1/recover?redirect_to=${encode(AUTH_REDIRECT)}",
                SupabasePkce.recoveryBody(email.trim(), pkce))
        } catch (failure: Exception) {
            runCatching { pendingPkce.clear() }
            throw failure
        }
    }

    fun acceptAuthCallback(uri: android.net.Uri): String {
        val kind = SupabasePkceCallbackFlow.complete(uri.toString(), pendingPkce) { code, verifier ->
            val token = request("POST", SupabasePkce.TOKEN_EXCHANGE_PATH, SupabasePkce.tokenExchangeBody(code, verifier))
            val accessToken = token.optString("access_token").takeIf(String::isNotBlank)
                ?: error("PKCE token exchange returned no session.")
            val session = JSONObject(token.toString())
            session.put("expires_at", System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600L))
            session.put("user", request("GET", "/auth/v1/user", null, bearer = accessToken))
            saveSession(session)
        }
        return when (kind) {
            PendingAuthKind.EMAIL_CONFIRMATION -> "signup"
            PendingAuthKind.PASSWORD_RECOVERY -> "recovery"
        }
    }

    fun updatePassword(password: String) {
        authorizedRequest("PUT", "/auth/v1/user", JSONObject().put("password", password))
    }

    fun signOut() {
        runCatching { authorizedRequest("POST", "/auth/v1/logout", JSONObject()) }
        runCatching { pendingPkce.clear() }
        prefs.edit().remove(SESSION_KEY).apply()
    }

    fun readCloudBackup(expectedUserId: String? = null): JSONObject {
        requireSession()
        return authorizedRequest("POST", "/rest/v1/rpc/app_read_my_backup", JSONObject(), expectedUserId = expectedUserId)
    }

    fun writeCloudBackup(snapshot: JSONObject, expectedUserId: String? = null) {
        val auth = requireSession()
        val userId = auth.optJSONObject("user")?.optString("id")
            ?.takeIf { !it.isNullOrBlank() && it != "null" }
            ?: error("The account session has no user identifier. Sign in again.")
        check(expectedUserId == null || expectedUserId == userId) {
            "The signed-in Supabase account changed before backup. No data was sent."
        }
        val profiles = snapshot.optJSONObject("profiles")?.optJSONArray("items") ?: JSONArray()
        val schedules = snapshot.optJSONObject("schedules")?.optJSONArray("items") ?: JSONArray()
        val modes = snapshot.optJSONObject("focusModes")?.optJSONArray("items") ?: JSONArray()
        val payload = JSONObject()
            .put("p_settings", snapshot.optJSONObject("settings") ?: JSONObject())
            .put("p_profiles", profiles)
            .put("p_schedules", schedules)
            .put("p_focus_modes", modes)
        authorizedRequest("POST", "/rest/v1/rpc/app_replace_my_backup", payload, expectedUserId = expectedUserId)
    }

    private fun requireSession(): JSONObject {
        check(isConfigured) { "Supabase is not configured on this device." }
        var active = session() ?: error("Sign in to your account first.")
        val expiresAt = active.optLong("expires_at", 0L)
        if (expiresAt > 0 && expiresAt < System.currentTimeMillis() / 1000 + 60) {
            val refresh = active.optString("refresh_token")
            check(refresh.isNotBlank()) { "Session expired. Sign in again." }
            active = request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh))
            check(active.has("access_token")) { "Session expired. Sign in again." }
            saveSession(active)
        }
        return active
    }

    private fun authorizedRequest(
        method: String,
        path: String,
        body: Any?,
        extraHeaders: Map<String, String> = emptyMap(),
        expectedUserId: String? = null,
    ): JSONObject {
        val active = requireSession()
        val activeUserId = active.optJSONObject("user")?.optString("id")
        check(expectedUserId == null || expectedUserId == activeUserId) {
            "The signed-in Supabase account changed before backup. No data was sent."
        }
        return request(method, path, body, active.optString("access_token"), extraHeaders)
    }

    private fun request(
        method: String,
        path: String,
        body: Any?,
        bearer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        check(isConfigured) { "Not configured: copy supabase.properties.example to supabase.properties and add the project URL and publishable key." }
        val connection = (URL(BuildConfig.SUPABASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val errorBody = runCatching { JSONObject(raw) }.getOrNull()
                val details = errorBody?.optString("msg")?.takeIf { it.isNotBlank() }
                    ?: errorBody?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: errorBody?.optString("error_description")?.takeIf { it.isNotBlank() }
                    ?: errorBody?.optString("error")?.takeIf { it.isNotBlank() }
                if (code == 401 || (code == 400 && raw.contains("invalid_grant", ignoreCase = true))) {
                    error("Supabase session expired or was revoked. Sign in again; local data remains available.")
                }
                error(details ?: "Supabase request failed (HTTP $code).")
            }
            when {
                raw.isBlank() -> JSONObject()
                raw.trimStart().startsWith("[") -> JSONObject().put("rows", JSONArray(raw))
                else -> JSONObject(raw)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveSession(response: JSONObject) {
        val session = JSONObject(response.toString())
        if (!session.has("expires_at") && session.has("expires_in")) {
            session.put("expires_at", System.currentTimeMillis() / 1000 + session.optLong("expires_in"))
        }
        prefs.edit().putString(SESSION_KEY, sessionCipher.encrypt(session.toString())).apply()
    }

    private fun session(): JSONObject? = prefs.getString(SESSION_KEY, null)?.let { encrypted ->
        runCatching { JSONObject(sessionCipher.decrypt(encrypted)) }.getOrNull()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private class AndroidSessionCipher {
        fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }

        fun decrypt(value: String): String {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            return generator.generateKey()
        }
    }

    companion object {
        private const val SESSION_KEY = "encrypted_session"
        private const val PKCE_STATE_KEY = "encrypted_pending_pkce_auth"
        private const val KEY_ALIAS = "app_internet_control_supabase_session"
        private const val AUTH_REDIRECT = "appinternetcontrol://auth-callback"
    }
}
