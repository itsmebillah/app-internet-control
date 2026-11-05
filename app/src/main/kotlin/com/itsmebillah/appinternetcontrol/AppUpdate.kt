package com.itsmebillah.appinternetcontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

data class UpdateManifest(
    val latestVersionName: String,
    val latestVersionCode: Long,
    val releaseNotes: String,
    val minimumSupportedVersionCode: Long,
    val releaseUrl: String,
)

data class UpdateOffer(
    val versionName: String,
    val versionCode: Long,
    val releaseNotes: String,
    val minimumSupportedVersionCode: Long,
    val releaseUrl: String,
)

object UpdateManifestParser {
    private const val MAX_MANIFEST_LENGTH = 16_384
    private const val MAX_RELEASE_NOTES_LENGTH = 4_000

    fun parse(jsonText: String): UpdateManifest {
        if (jsonText.length !in 1..MAX_MANIFEST_LENGTH) {
            throw InvalidUpdateManifestException("Manifest size is invalid.")
        }
        return try {
            val json = JSONObject(jsonText)
            val manifest = UpdateManifest(
                latestVersionName = json.getString("latestVersionName").trim(),
                latestVersionCode = json.getLong("latestVersionCode"),
                releaseNotes = json.getString("releaseNotes").trim(),
                minimumSupportedVersionCode = json.getLong("minimumSupportedVersionCode"),
                releaseUrl = json.getString("releaseUrl").trim(),
            )
            if (manifest.latestVersionName.isBlank() || manifest.latestVersionName.length > 64) {
                throw InvalidUpdateManifestException("Version name is invalid.")
            }
            if (manifest.latestVersionCode < 1 || manifest.minimumSupportedVersionCode < 1 ||
                manifest.minimumSupportedVersionCode > manifest.latestVersionCode
            ) {
                throw InvalidUpdateManifestException("Version codes are invalid.")
            }
            if (manifest.releaseNotes.isBlank() || manifest.releaseNotes.length > MAX_RELEASE_NOTES_LENGTH) {
                throw InvalidUpdateManifestException("Release notes are invalid.")
            }
            if (!isHttpsUrl(manifest.releaseUrl)) {
                throw InvalidUpdateManifestException("Release URL must use HTTPS.")
            }
            manifest
        } catch (error: InvalidUpdateManifestException) {
            throw error
        } catch (error: Exception) {
            throw InvalidUpdateManifestException("Manifest fields are invalid.", error)
        }
    }

    fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)
}

enum class UpdateFailure { NETWORK, INVALID_METADATA, UNAVAILABLE }

sealed interface UpdateCheckResult {
    data object NotConfigured : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Deferred : UpdateCheckResult
    data class Available(val offer: UpdateOffer, val required: Boolean) : UpdateCheckResult
    data class Failed(val reason: UpdateFailure) : UpdateCheckResult
}

class InvalidUpdateManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)
class UpdateManifestUnavailableException : IOException("Update manifest is unavailable.")

fun interface UpdateManifestSource {
    @Throws(IOException::class, InvalidUpdateManifestException::class)
    fun fetch(manifestUrl: String): UpdateManifest
}

interface AppUpdateProvider {
    val isConfigured: Boolean
    fun check(installedVersionCode: Long): UpdateCheckResult
    fun launchUpdate(context: Context, offer: UpdateOffer)
}

class ManifestAppUpdateProvider(
    private val manifestUrl: String,
    private val source: UpdateManifestSource = HttpsUpdateManifestSource(),
) : AppUpdateProvider {
    override val isConfigured: Boolean get() = manifestUrl.isNotBlank()

    override fun check(installedVersionCode: Long): UpdateCheckResult {
        if (manifestUrl.isBlank()) return UpdateCheckResult.NotConfigured
        if (!UpdateManifestParser.isHttpsUrl(manifestUrl)) {
            return UpdateCheckResult.Failed(UpdateFailure.INVALID_METADATA)
        }
        return try {
            val manifest = source.fetch(manifestUrl)
            UpdatePolicy.evaluate(installedVersionCode, manifest)
        } catch (_: InvalidUpdateManifestException) {
            UpdateCheckResult.Failed(UpdateFailure.INVALID_METADATA)
        } catch (_: UpdateManifestUnavailableException) {
            UpdateCheckResult.Failed(UpdateFailure.UNAVAILABLE)
        } catch (_: IOException) {
            UpdateCheckResult.Failed(UpdateFailure.NETWORK)
        } catch (_: Exception) {
            UpdateCheckResult.Failed(UpdateFailure.NETWORK)
        }
    }

    override fun launchUpdate(context: Context, offer: UpdateOffer) {
        if (!UpdateManifestParser.isHttpsUrl(offer.releaseUrl)) return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offer.releaseUrl)))
    }
}

class HttpsUpdateManifestSource : UpdateManifestSource {
    override fun fetch(manifestUrl: String): UpdateManifest {
        if (!UpdateManifestParser.isHttpsUrl(manifestUrl)) {
            throw InvalidUpdateManifestException("Manifest URL must use HTTPS.")
        }
        val connection = (URI(manifestUrl).toURL().openConnection() as? HttpsURLConnection)
            ?: throw InvalidUpdateManifestException("Manifest URL must use HTTPS.")
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            when (connection.responseCode) {
                in 200..299 -> Unit
                404 -> throw UpdateManifestUnavailableException()
                else -> throw IOException("Update manifest request failed.")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(2_048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw InvalidUpdateManifestException("Manifest is too large.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return UpdateManifestParser.parse(String(output.toByteArray(), StandardCharsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 5_000
        private const val MAX_RESPONSE_BYTES = 16_384
    }
}

object UpdatePolicy {
    fun evaluate(installedVersionCode: Long, manifest: UpdateManifest): UpdateCheckResult = when {
        installedVersionCode < manifest.minimumSupportedVersionCode ->
            UpdateCheckResult.Available(manifest.toOffer(), required = true)
        installedVersionCode < manifest.latestVersionCode ->
            UpdateCheckResult.Available(manifest.toOffer(), required = false)
        else -> UpdateCheckResult.UpToDate
    }

    private fun UpdateManifest.toOffer() = UpdateOffer(
        versionName = latestVersionName,
        versionCode = latestVersionCode,
        releaseNotes = releaseNotes,
        minimumSupportedVersionCode = minimumSupportedVersionCode,
        releaseUrl = releaseUrl,
    )
}

object UpdateReminderPolicy {
    fun shouldCheck(nowMillis: Long, lastAttemptMillis: Long, intervalMillis: Long): Boolean =
        lastAttemptMillis <= 0 || nowMillis < lastAttemptMillis || nowMillis - lastAttemptMillis >= intervalMillis

    fun shouldSuppressOptionalPrompt(
        latestVersionCode: Long,
        deferredVersionCode: Long,
        deferredAtMillis: Long,
        nowMillis: Long,
        intervalMillis: Long,
    ): Boolean = latestVersionCode == deferredVersionCode &&
        deferredAtMillis > 0 && nowMillis >= deferredAtMillis && nowMillis - deferredAtMillis < intervalMillis

    fun shouldPrompt(
        installedVersionCode: Long,
        latestVersionCode: Long,
        minimumSupportedVersionCode: Long,
        deferredVersionCode: Long,
        deferredAtMillis: Long,
        nowMillis: Long,
        laterIntervalMillis: Long,
    ): Boolean = installedVersionCode < minimumSupportedVersionCode ||
        !shouldSuppressOptionalPrompt(
            latestVersionCode,
            deferredVersionCode,
            deferredAtMillis,
            nowMillis,
            laterIntervalMillis,
        )
}
