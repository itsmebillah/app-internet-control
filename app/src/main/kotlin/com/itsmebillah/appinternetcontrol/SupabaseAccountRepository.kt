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
import java.net.URLDecoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supabase Auth/PostgREST client using only the public publishable key. */
class SupabaseAccountRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)
    private val sessionCipher = AndroidSessionCipher()

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
        val result = request("POST", "/auth/v1/signup?redirect_to=${encode(AUTH_REDIRECT)}", JSONObject()
            .put("email", email.trim()).put("password", password))
        if (result.has("access_token")) saveSession(result)
        return if (result.has("access_token")) "Account created and signed in."
        else "Sign-up submitted. Confirm your email before signing in if email confirmation is enabled."
    }

    fun signIn(email: String, password: String) {
        val result = request("POST", "/auth/v1/token?grant_type=password", JSONObject()
            .put("email", email.trim()).put("password", password))
        check(result.has("access_token")) { "Sign-in did not return an active session. Check email confirmation and try again." }
        saveSession(result)
    }

    fun requestPasswordRecovery(email: String) {
        request("POST", "/auth/v1/recover?redirect_to=${encode(AUTH_REDIRECT)}", JSONObject().put("email", email.trim()))
    }

    fun acceptAuthCallback(uri: android.net.Uri): String? {
        val fragment = uri.fragment ?: return null
        val values = fragment.split('&').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size == 2) URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") else null
        }.toMap()
        values["error_description"]?.let { error("Authentication callback failed: $it") }
        val accessToken = values["access_token"] ?: return null
        val refreshToken = values["refresh_token"].orEmpty()
        val session = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken)
            .put("expires_in", values["expires_in"]?.toLongOrNull() ?: 3600L)
            .put("expires_at", System.currentTimeMillis() / 1000 + (values["expires_in"]?.toLongOrNull() ?: 3600L))
        saveSession(session)
        val user = authorizedRequest("GET", "/auth/v1/user", null)
        session.put("user", user)
        saveSession(session)
        return values["type"]
    }

    fun updatePassword(password: String) {
        authorizedRequest("PUT", "/auth/v1/user", JSONObject().put("password", password))
    }

    fun signOut() {
        runCatching { authorizedRequest("POST", "/auth/v1/logout", JSONObject()) }
        prefs.edit().remove(SESSION_KEY).apply()
    }

    fun readCloudBackup(): JSONObject {
        val auth = requireSession()
        val userId = auth.optJSONObject("user")?.optString("id")
            ?.takeIf { !it.isNullOrBlank() && it != "null" }
            ?: error("The account session has no user identifier. Sign in again.")
        val result = JSONObject()
        BACKUP_TABLES.forEach { (table, key) ->
            val columns = if (table == "app_cloud_settings") "payload" else "record_id,payload"
            val ordering = if (table == "app_cloud_settings") "" else "&order=record_id.asc"
            val query = "?select=$columns&user_id=eq.${encode(userId)}$ordering"
            val rows = authorizedRequest("GET", "/rest/v1/$table$query", null)
            val values = rows.optJSONArray("rows") ?: JSONArray()
            if (table == "app_cloud_settings") {
                result.put(key, values.optJSONObject(0)?.optJSONObject("payload") ?: JSONObject())
            } else {
                val items = JSONArray()
                for (index in 0 until values.length()) values.optJSONObject(index)?.optJSONObject("payload")?.let(items::put)
                result.put(key, JSONObject().put("items", items))
            }
        }
        return result
    }

    fun writeCloudBackup(snapshot: JSONObject) {
        val auth = requireSession()
        val userId = auth.optJSONObject("user")?.optString("id")
            ?.takeIf { !it.isNullOrBlank() && it != "null" }
            ?: error("The account session has no user identifier. Sign in again.")
        val profiles = snapshot.optJSONObject("profiles")?.optJSONArray("items") ?: JSONArray()
        val schedules = snapshot.optJSONObject("schedules")?.optJSONArray("items") ?: JSONArray()
        val modes = snapshot.optJSONObject("focusModes")?.optJSONArray("items") ?: JSONArray()
        val payload = JSONObject()
            .put("p_settings", snapshot.optJSONObject("settings") ?: JSONObject())
            .put("p_profiles", profiles)
            .put("p_schedules", schedules)
            .put("p_focus_modes", modes)
        authorizedRequest("POST", "/rest/v1/rpc/app_replace_my_backup", payload)
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

    private fun authorizedRequest(method: String, path: String, body: Any?, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val token = requireSession().optString("access_token")
        return request(method, path, body, token, extraHeaders)
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
                val details = runCatching { JSONObject(raw).optString("msg").ifBlank { JSONObject(raw).optString("message") } }.getOrDefault("")
                error(details.takeIf { it.isNotBlank() } ?: "Supabase request failed (HTTP $code).")
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
        private const val KEY_ALIAS = "app_internet_control_supabase_session"
        private const val AUTH_REDIRECT = "appinternetcontrol://auth-callback"
        private val BACKUP_TABLES = listOf(
            "app_cloud_settings" to "settings",
            "app_cloud_profiles" to "profiles",
            "app_cloud_schedules" to "schedules",
            "app_cloud_focus_modes" to "focusModes",
        )
    }
}
