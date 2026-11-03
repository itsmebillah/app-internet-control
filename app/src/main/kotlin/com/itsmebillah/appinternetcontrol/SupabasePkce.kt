package com.itsmebillah.appinternetcontrol

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

internal enum class PendingAuthKind { EMAIL_CONFIRMATION, PASSWORD_RECOVERY }

internal data class PkceRequest(val codeChallenge: String)
internal data class PendingPkceAuth(val kind: PendingAuthKind, val verifier: String)

internal object SupabasePkce {
    const val TOKEN_EXCHANGE_PATH = "/auth/v1/token?grant_type=pkce"
    const val CODE_CHALLENGE_METHOD = "s256"
    private val random = SecureRandom()

    fun createRequest(): Pair<String, PkceRequest> {
        val entropy = ByteArray(32).also(random::nextBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
        return verifier to PkceRequest(codeChallenge(verifier))
    }

    fun codeChallenge(verifier: String): String {
        require(verifier.length in 43..128 && verifier.matches(Regex("[A-Za-z0-9._~-]+")))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )
    }

    fun tokenExchangeBody(authorizationCode: String, verifier: String): JSONObject = JSONObject()
        .put("auth_code", authorizationCode)
        .put("code_verifier", verifier)

    fun signupBody(email: String, password: String, request: PkceRequest): JSONObject = JSONObject()
        .put("email", email)
        .put("password", password)
        .put("code_challenge", request.codeChallenge)
        .put("code_challenge_method", CODE_CHALLENGE_METHOD)

    fun recoveryBody(email: String, request: PkceRequest): JSONObject = JSONObject()
        .put("email", email)
        .put("code_challenge", request.codeChallenge)
        .put("code_challenge_method", CODE_CHALLENGE_METHOD)
}

internal interface PkceAuthPersistence {
    fun readEncrypted(): String?
    fun writeEncrypted(value: String): Boolean
    fun clear(): Boolean
}

/** Stores one pending flow at a time; the Android adapter encrypts values before persistence. */
internal class PendingPkceAuthStore(
    private val persistence: PkceAuthPersistence,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
    private val createRequest: () -> Pair<String, PkceRequest> = SupabasePkce::createRequest,
) {
    fun begin(kind: PendingAuthKind): PkceRequest {
        val (verifier, request) = createRequest()
        val encodedState = "${kind.name}\n$verifier"
        if (!runCatching { persistence.writeEncrypted(encrypt(encodedState)) }.getOrDefault(false)) {
            throw PkceAuthException("Could not safely save the authentication flow. Please try again.")
        }
        return request
    }

    /** Removes the saved state before returning it, so a callback can be consumed only once. */
    fun consume(): PendingPkceAuth? {
        val saved = persistence.readEncrypted() ?: return null
        if (!runCatching { persistence.clear() }.getOrDefault(false)) {
            throw PkceAuthException("Could not safely clear the authentication flow. Please start again.")
        }
        val decoded = runCatching { decrypt(saved) }.getOrNull()
            ?: throw PkceAuthException("The saved authentication flow is unavailable. Please start again.")
        val separator = decoded.indexOf('\n')
        if (separator <= 0 || separator == decoded.lastIndex) {
            throw PkceAuthException("The saved authentication flow is invalid. Please start again.")
        }
        val kind = runCatching { PendingAuthKind.valueOf(decoded.substring(0, separator)) }.getOrNull()
            ?: throw PkceAuthException("The saved authentication flow is invalid. Please start again.")
        val verifier = decoded.substring(separator + 1)
        if (verifier.length !in 43..128) {
            throw PkceAuthException("The saved authentication flow is invalid. Please start again.")
        }
        return PendingPkceAuth(kind, verifier)
    }

    fun clear() {
        if (!runCatching { persistence.clear() }.getOrDefault(false)) {
            throw PkceAuthException("Could not safely clear the authentication flow. Please try again.")
        }
    }
}

internal sealed interface PkceCallback {
    data class Code(val value: String) : PkceCallback
    data object ProviderError : PkceCallback
    data object Invalid : PkceCallback
}

internal object SupabaseCallbackParser {
    fun parse(rawUri: String): PkceCallback {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return PkceCallback.Invalid
        if (uri.scheme?.equals("appinternetcontrol", ignoreCase = true) != true ||
            uri.host?.equals("auth-callback", ignoreCase = true) != true ||
            uri.rawPath.orEmpty().isNotEmpty() || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawFragment != null || uri.isOpaque
        ) return PkceCallback.Invalid

        val parameters = runCatching {
            uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { part ->
                val separator = part.indexOf('=')
                val key = if (separator < 0) part else part.substring(0, separator)
                val value = if (separator < 0) "" else part.substring(separator + 1)
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
        }.getOrNull() ?: return PkceCallback.Invalid

        if (parameters.any { it.first in setOf("error", "error_code", "error_description") }) {
            return PkceCallback.ProviderError
        }
        val codes = parameters.filter { it.first == "code" }.map { it.second }
        return if (codes.size == 1 && codes.single().isNotBlank()) {
            PkceCallback.Code(codes.single())
        } else {
            PkceCallback.Invalid
        }
    }
}

internal class PkceAuthException(message: String) : Exception(message)

/** Completes one callback without ever placing its code or verifier in an exception message. */
internal object SupabasePkceCallbackFlow {
    fun complete(
        rawUri: String,
        pendingAuth: PendingPkceAuthStore,
        exchange: (authorizationCode: String, codeVerifier: String) -> Unit,
    ): PendingAuthKind {
        return when (val callback = SupabaseCallbackParser.parse(rawUri)) {
            PkceCallback.Invalid -> throw PkceAuthException("This authentication link is invalid. Request a new link from the app.")
            PkceCallback.ProviderError -> {
                pendingAuth.clear()
                throw PkceAuthException("The authentication link was declined or expired. Please start again.")
            }
            is PkceCallback.Code -> {
                val pending = pendingAuth.consume()
                    ?: throw PkceAuthException("No matching authentication request is pending. Start again in the app.")
                try {
                    exchange(callback.value, pending.verifier)
                } catch (_: Exception) {
                    throw PkceAuthException("The authentication link could not be completed. Request a new link from the app.")
                }
                pending.kind
            }
        }
    }
}
