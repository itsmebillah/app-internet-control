package com.itsmebillah.appinternetcontrol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

data class UsageWindow(
    val date: LocalDate,
    val startMillis: Long,
    val endMillis: Long,
    val records: List<UsageRecord>,
)

class UsageHistoryDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usage_daily (date TEXT NOT NULL, app_key TEXT NOT NULL, package_names TEXT NOT NULL, rx_bytes INTEGER NOT NULL, tx_bytes INTEGER NOT NULL, PRIMARY KEY(date, app_key))")
        db.execSQL("CREATE TABLE usage_coverage (date TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, PRIMARY KEY(date, start_ms, end_ms))")
        db.execSQL("CREATE TABLE usage_meta (key TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun isStarted(): Boolean = getMeta(COLLECTION_START_KEY) > 0 && getMeta(COLLECTION_ENABLED_KEY) == 1L
    fun collectionStartedAt(): Long = getMeta(COLLECTION_START_KEY)
    fun lastCapturedAt(): Long = getMeta(LAST_CAPTURE_KEY)
    fun hasCoverageGap(): Boolean = getMeta(COVERAGE_GAP_KEY) == 1L

    fun startCollection(nowMillis: Long = System.currentTimeMillis()) {
        val previousStart = collectionStartedAt()
        val wasCollecting = isStarted()
        val previousCapture = lastCapturedAt()
        writableDatabase.beginTransaction()
        try {
            if (previousStart == 0L) {
                setMeta(COLLECTION_START_KEY, nowMillis)
                setMeta(LAST_CAPTURE_KEY, nowMillis)
                setMeta(COVERAGE_GAP_KEY, 0)
            } else if (!wasCollecting) {
                if (nowMillis > previousCapture) {
                    setMeta(LAST_CAPTURE_KEY, nowMillis)
                    setMeta(COVERAGE_GAP_KEY, 1)
                }
            }
            setMeta(COLLECTION_ENABLED_KEY, 1)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun stopCollection() = setMeta(COLLECTION_ENABLED_KEY, 0)

    fun applyCapture(windows: List<UsageWindow>, capturedAtMillis: Long, coverageGap: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            windows.forEach { window ->
                db.insertWithOnConflict("usage_coverage", null, ContentValues().apply {
                    put("date", window.date.toString())
                    put("start_ms", window.startMillis)
                    put("end_ms", window.endMillis)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                window.records.forEach { record ->
                    val appKey = record.packageNames.toSortedSet().joinToString("|")
                    val oldTotals = db.rawQuery(
                        "SELECT rx_bytes, tx_bytes FROM usage_daily WHERE date=? AND app_key=?",
                        arrayOf(record.date.toString(), appKey),
                    ).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else 0L to 0L }
                    db.insertWithOnConflict("usage_daily", null, ContentValues().apply {
                        put("date", record.date.toString())
                        put("app_key", appKey)
                        put("package_names", record.packageNames.sorted().joinToString("\n"))
                        put("rx_bytes", oldTotals.first + record.receivedBytes)
                        put("tx_bytes", oldTotals.second + record.transmittedBytes)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            setMeta(LAST_CAPTURE_KEY, capturedAtMillis)
            if (coverageGap) setMeta(COVERAGE_GAP_KEY, 1)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readRecords(): List<UsageRecord> = readableDatabase.rawQuery(
        "SELECT date, package_names, rx_bytes, tx_bytes FROM usage_daily ORDER BY date",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(UsageRecord(
                    date = LocalDate.parse(cursor.getString(0)),
                    packageNames = cursor.getString(1).split('\n').toSet(),
                    receivedBytes = cursor.getLong(2),
                    transmittedBytes = cursor.getLong(3),
                ))
            }
        }
    }

    fun readCoveredMillis(date: LocalDate): Long = readableDatabase.rawQuery(
        "SELECT start_ms, end_ms FROM usage_coverage WHERE date=?",
        arrayOf(date.toString()),
    ).use { cursor ->
        var covered = 0L
        while (cursor.moveToNext()) covered += (cursor.getLong(1) - cursor.getLong(0)).coerceAtLeast(0)
        covered
    }

    fun clearHistory(nowMillis: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("usage_daily", null, null)
            db.delete("usage_coverage", null, null)
            setMeta(COLLECTION_START_KEY, nowMillis)
            setMeta(LAST_CAPTURE_KEY, nowMillis)
            setMeta(COVERAGE_GAP_KEY, 0)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun getMeta(key: String): Long = readableDatabase.rawQuery(
        "SELECT value FROM usage_meta WHERE key=?", arrayOf(key),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun setMeta(key: String, value: Long) {
        writableDatabase.insertWithOnConflict("usage_meta", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val DATABASE_NAME = "usage_history.db"
        private const val DATABASE_VERSION = 1
        private const val COLLECTION_START_KEY = "collection_start"
        private const val LAST_CAPTURE_KEY = "last_capture"
        private const val COVERAGE_GAP_KEY = "coverage_gap"
        private const val COLLECTION_ENABLED_KEY = "collection_enabled"
    }
}
