package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppUpdateTest {
    private val manifest = UpdateManifest(
        latestVersionName = "1.2.0",
        latestVersionCode = 12,
        releaseNotes = "Reliability improvements.",
        minimumSupportedVersionCode = 9,
        releaseUrl = "https://github.com/example/app/releases/tag/v1.2.0",
    )
    private val offer = UpdateOffer("1.2.0", 12, "Reliability improvements.", 9, manifest.releaseUrl)

    @Test
    fun newerVersionCodeOffersAnOptionalUpdate() {
        val result = UpdatePolicy.evaluate(installedVersionCode = 11, manifest)
        assertEquals(UpdateCheckResult.Available(offer, required = false), result)
    }

    @Test
    fun equalOrOlderLatestVersionCodeDoesNotOfferAnUpdate() {
        assertEquals(UpdateCheckResult.UpToDate, UpdatePolicy.evaluate(12, manifest))
        assertEquals(UpdateCheckResult.UpToDate, UpdatePolicy.evaluate(13, manifest))
    }

    @Test
    fun versionOneInstallIsOfferedThePlannedVersionOneZeroOneRelease() {
        val firstPublicRelease = UpdateManifest(
            latestVersionName = "1.0.1",
            latestVersionCode = 2,
            releaseNotes = "Initial public release.",
            minimumSupportedVersionCode = 1,
            releaseUrl = "https://github.com/itsmebillah/app-internet-control/releases/latest",
        )

        val result = UpdatePolicy.evaluate(installedVersionCode = 1, firstPublicRelease)

        assertEquals(
            UpdateCheckResult.Available(
                UpdateOffer(
                    versionName = "1.0.1",
                    versionCode = 2,
                    releaseNotes = "Initial public release.",
                    minimumSupportedVersionCode = 1,
                    releaseUrl = "https://github.com/itsmebillah/app-internet-control/releases/latest",
                ),
                required = false,
            ),
            result,
        )
    }

    @Test
    fun versionBelowMinimumGetsRequiredUpdateEvenWhenItWasDeferred() {
        val result = UpdatePolicy.evaluate(installedVersionCode = 8, manifest)
        assertEquals(UpdateCheckResult.Available(offer, required = true), result)
        assertTrue(
            UpdateReminderPolicy.shouldPrompt(
                installedVersionCode = 8,
                latestVersionCode = 12,
                minimumSupportedVersionCode = 9,
                deferredVersionCode = 12,
                deferredAtMillis = 10_000,
                nowMillis = 11_000,
                laterIntervalMillis = 100_000,
            ),
        )
    }

    @Test
    fun manifestParserRejectsMissingFieldsInvalidVersionsAndNonHttpsLinks() {
        assertInvalidManifest("""{"latestVersionName":"1.2","latestVersionCode":12}""")
        assertInvalidManifest(validManifestJson().replace("\"minimumSupportedVersionCode\":9", "\"minimumSupportedVersionCode\":13"))
        assertInvalidManifest(validManifestJson().replace("https://", "http://"))
        assertFalse(UpdateManifestParser.isHttpsUrl("https://user:pass@example.com/updates.json"))
    }

    @Test
    fun networkFailureIsReturnedAsARecoverableResult() {
        val provider = ManifestAppUpdateProvider(
            manifestUrl = "https://updates.example.org/manifest.json",
            source = UpdateManifestSource { throw IOException("offline") },
        )
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.NETWORK), provider.check(installedVersionCode = 10))
    }

    @Test
    fun laterSuppressesOnlyTheSameOptionalVersionUntilItsIntervalExpires() {
        val withinLaterPeriod = UpdateReminderPolicy.shouldPrompt(
            installedVersionCode = 11,
            latestVersionCode = 12,
            minimumSupportedVersionCode = 9,
            deferredVersionCode = 12,
            deferredAtMillis = 10_000,
            nowMillis = 10_500,
            laterIntervalMillis = 1_000,
        )
        val afterLaterPeriod = UpdateReminderPolicy.shouldPrompt(
            installedVersionCode = 11,
            latestVersionCode = 12,
            minimumSupportedVersionCode = 9,
            deferredVersionCode = 12,
            deferredAtMillis = 10_000,
            nowMillis = 11_000,
            laterIntervalMillis = 1_000,
        )
        val forNewVersion = UpdateReminderPolicy.shouldPrompt(
            installedVersionCode = 11,
            latestVersionCode = 13,
            minimumSupportedVersionCode = 9,
            deferredVersionCode = 12,
            deferredAtMillis = 10_000,
            nowMillis = 10_500,
            laterIntervalMillis = 1_000,
        )

        assertFalse(withinLaterPeriod)
        assertTrue(afterLaterPeriod)
        assertTrue(forNewVersion)
    }

    @Test
    fun launchChecksAreRateLimitedButManualChecksCanBypassTheInterval() {
        assertFalse(UpdateReminderPolicy.shouldCheck(nowMillis = 15_000, lastAttemptMillis = 10_000, intervalMillis = 10_000))
        assertTrue(UpdateReminderPolicy.shouldCheck(nowMillis = 20_000, lastAttemptMillis = 10_000, intervalMillis = 10_000))
        assertTrue(UpdateReminderPolicy.shouldCheck(nowMillis = 5_000, lastAttemptMillis = 10_000, intervalMillis = 10_000))
    }

    private fun assertInvalidManifest(json: String) {
        try {
            UpdateManifestParser.parse(json)
            throw AssertionError("Expected invalid manifest to be rejected")
        } catch (_: InvalidUpdateManifestException) {
            // Expected validation result.
        }
    }

    private fun validManifestJson() = """{
        "latestVersionName":"1.2.0",
        "latestVersionCode":12,
        "releaseNotes":"Reliability improvements.",
        "minimumSupportedVersionCode":9,
        "releaseUrl":"https://github.com/example/app/releases/tag/v1.2.0"
    }"""
}
