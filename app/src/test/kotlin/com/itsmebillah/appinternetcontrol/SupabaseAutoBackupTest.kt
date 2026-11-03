package com.itsmebillah.appinternetcontrol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SupabaseAutoBackupTest {
    @Test
    fun automaticUploadRequiresConfiguredSignedInAndExplicitlyEnabledState() {
        assertFalse(SupabaseAutoBackupPolicy.mayRun(signedIn = false, enabled = true, configured = true))
        assertFalse(SupabaseAutoBackupPolicy.mayRun(signedIn = true, enabled = false, configured = true))
        assertFalse(SupabaseAutoBackupPolicy.mayRun(signedIn = true, enabled = true, configured = false))
        assertTrue(SupabaseAutoBackupPolicy.mayRun(signedIn = true, enabled = true, configured = true))
    }

    @Test
    fun automaticSyncUploadsOnlyWhenRemoteHasNotChangedSinceBaseline() {
        assertEquals(AutoBackupDecision.UPLOAD_LOCAL, SupabaseAutoBackupPolicy.decision("local", null, null))
        assertEquals(AutoBackupDecision.UPLOAD_LOCAL, SupabaseAutoBackupPolicy.decision("local-new", "base", "base"))
        assertEquals(AutoBackupDecision.ALREADY_CURRENT, SupabaseAutoBackupPolicy.decision("same", "same", "old"))
        assertEquals(AutoBackupDecision.CLOUD_CHANGED, SupabaseAutoBackupPolicy.decision("base", "cloud-new", "base"))
        assertEquals(AutoBackupDecision.CONFLICT, SupabaseAutoBackupPolicy.decision("local-new", "cloud-new", "base"))
        assertEquals(AutoBackupDecision.CONFLICT, SupabaseAutoBackupPolicy.decision("local", "cloud", null))
    }

    @Test
    fun fingerprintIsStableAcrossJsonObjectKeyOrdering() {
        val first = JSONObject("""{"settings":{"backupSchemaVersion":1,"blockedPackages":["b","a"]},"profiles":{"items":[]}}""")
        val second = JSONObject("""{"profiles":{"items":[]},"settings":{"blockedPackages":["b","a"],"backupSchemaVersion":1}}""")
        val differentArrayOrder = JSONObject("""{"profiles":{"items":[]},"settings":{"blockedPackages":["a","b"],"backupSchemaVersion":1}}""")
        assertEquals(CloudBackupFingerprint.of(first), CloudBackupFingerprint.of(second))
        assertTrue(CloudBackupFingerprint.of(first) != CloudBackupFingerprint.of(differentArrayOrder))
    }

    @Test
    fun serializedBackupCanBeValidatedIntoARecoverableRestorePlan() {
        val snapshot = JSONObject()
            .put("settings", JSONObject().put("backupSchemaVersion", 1)
                .put("blockedPackages", JSONArray().put("manual.app")).put("activeProfileId", "work"))
            .put("profiles", JSONObject().put("items", JSONArray().put(
                JSONObject().put("id", "work").put("name", "Work")
                    .put("blocked", JSONArray().put("work.app")).put("allowed", JSONArray()),
            )))
            .put("schedules", JSONObject().put("items", JSONArray()))
            .put("focusModes", JSONObject().put("items", JSONArray()))
        val restore = CloudBackupSnapshot.prepareRestore(JSONObject(snapshot.toString()))
        assertEquals(setOf("manual.app"), restore.selection.packageNames)
        assertEquals("work", restore.activeProfileId)
        assertEquals(setOf("work.app"), restore.profiles.single().blockedPackageNames)
        assertFalse(snapshot.has("usageHistory"))
    }

    @Test
    fun unsupportedBackupSchemaIsRejectedBeforeRestore() {
        val unsupported = JSONObject()
            .put("settings", JSONObject().put("backupSchemaVersion", 900).put("blockedPackages", JSONArray()))
            .put("profiles", JSONObject().put("items", JSONArray()))
            .put("schedules", JSONObject().put("items", JSONArray()))
            .put("focusModes", JSONObject().put("items", JSONArray()))
        assertThrows(IllegalArgumentException::class.java) { CloudBackupSnapshot.validate(unsupported) }
    }

    @Test
    fun failedUploadOrVerificationDoesNotAdvanceBaselineOrLastSuccess() {
        var baseline = "previous"
        var lastSuccess = 1_000L
        assertThrows(IOException::class.java) {
            SupabaseBackupAttemptPolicy.run(
                uploadAndVerify = { throw IOException("network unavailable") },
                markSuccess = { baseline = "new"; lastSuccess = 2_000L },
            )
        }
        assertEquals("previous", baseline)
        assertEquals(1_000L, lastSuccess)
    }
}
