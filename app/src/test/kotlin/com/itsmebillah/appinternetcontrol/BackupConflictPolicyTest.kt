package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupConflictPolicyTest {
    @Test fun matchingCopiesNeedNoReplacement() {
        assertEquals(BackupConflict.MATCH, BackupConflictPolicy.compare("a", "a", "old"))
    }

    @Test fun firstSyncRequiresAnExplicitDirection() {
        assertEquals(BackupConflict.FIRST_SYNC, BackupConflictPolicy.compare("local", "cloud", null))
    }

    @Test fun detectsOnlyLocalChanges() {
        assertEquals(BackupConflict.LOCAL_CHANGED, BackupConflictPolicy.compare("new", "old", "old"))
    }

    @Test fun detectsOnlyCloudChanges() {
        assertEquals(BackupConflict.CLOUD_CHANGED, BackupConflictPolicy.compare("old", "new", "old"))
    }

    @Test fun detectsConcurrentChanges() {
        assertEquals(BackupConflict.BOTH_CHANGED, BackupConflictPolicy.compare("local", "cloud", "previous"))
    }
}
