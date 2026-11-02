package com.itsmebillah.appinternetcontrol

enum class BackupConflict { MATCH, FIRST_SYNC, LOCAL_CHANGED, CLOUD_CHANGED, BOTH_CHANGED }

object BackupConflictPolicy {
    fun compare(localHash: String, cloudHash: String, lastSyncedHash: String?): BackupConflict = when {
        localHash == cloudHash -> BackupConflict.MATCH
        lastSyncedHash == null -> BackupConflict.FIRST_SYNC
        localHash != lastSyncedHash && cloudHash != lastSyncedHash -> BackupConflict.BOTH_CHANGED
        localHash != lastSyncedHash -> BackupConflict.LOCAL_CHANGED
        else -> BackupConflict.CLOUD_CHANGED
    }
}
