package com.trafficguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileWriter

/**
 * DNS 로그 + 위험 판정 이력 저장/조회/CSV export.
 */
object LogStore {

    private var helper: DbHelper? = null

    private fun getHelper(context: Context): DbHelper {
        if (helper == null) helper = DbHelper(context.applicationContext)
        return helper!!
    }

    fun insert(context: Context, entry: DnsLogEntry) {
        val db = getHelper(context).writableDatabase
        val cv = ContentValues().apply {
            put("timestamp", entry.timestamp)
            put("app_package", entry.appPackage)
            put("domain", entry.domain)
            put("dns_server", entry.dnsServer)
            put("cell_type", entry.cellNetworkType)
            put("mcc", entry.mcc)
            put("mnc", entry.mnc)
            put("cell_id", entry.cellId)
            put("area_code", entry.areaCode)
            put("pci", entry.pci)
            put("signal_dbm", entry.signalDbm)
        }
        db.insert("dns_log", null, cv)
    }

    // ---------------- 위험 판정(분석) 이력 ----------------

    fun insertRiskAlert(context: Context, entry: RiskAlertEntry) {
        val db = getHelper(context).writableDatabase
        val cv = ContentValues().apply {
            put("timestamp", entry.timestamp)
            put("app_package", entry.appPackage)
            put("target", entry.target)
            put("category", entry.category)
            put("reason", entry.reason)
        }
        db.insert("risk_alert_log", null, cv)
    }

    /** 위험 판정 이력이 존재하는 날짜 목록 (최신순) */
    fun getAlertDates(context: Context): List<String> {
        val db = getHelper(context).readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT DISTINCT date(timestamp / 1000, 'unixepoch', 'localtime') AS d
            FROM risk_alert_log
            ORDER BY d DESC
            """.trimIndent(),
            null
        )
        val dates = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) dates.add(it.getString(0))
        }
        return dates
    }

    /** 특정 날짜의 위험 판정 이력을 최신순으로 반환 */
    fun getAlertsForDate(context: Context, date: String): List<RiskAlertEntry> {
        val db = getHelper(context).readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT timestamp, app_package, target, category, reason
            FROM risk_alert_log
            WHERE date(timestamp / 1000, 'unixepoch', 'localtime') = ?
            ORDER BY timestamp DESC
            """.trimIndent(),
            arrayOf(date)
        )
        val list = mutableListOf<RiskAlertEntry>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    RiskAlertEntry(
                        timestamp = it.getLong(0),
                        appPackage = it.getString(1) ?: "unknown",
                        target = it.getString(2) ?: "",
                        category = it.getString(3) ?: "",
                        reason = it.getString(4) ?: ""
                    )
                )
            }
        }
        return list
    }

    // ---------------- 기존 DNS 로그 조회 ----------------

    /** 특정 시각 이후 DNS 조회 기록이 있었던 패키지명 집합 (SYSTEM_HEARTBEAT는 제외) */
    fun getPackagesWithActivitySince(context: Context, sinceMs: Long): Set<String> {
        val db = getHelper(context).readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT DISTINCT app_package FROM dns_log
            WHERE timestamp >= ? AND app_package != 'SYSTEM_HEARTBEAT'
            """.trimIndent(),
            arrayOf(sinceMs.toString())
        )
        val result = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) result.add(it.getString(0))
        }
        return result
    }

    fun getAvailableDates(context: Context): List<String> {
        val db = getHelper(context).readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT DISTINCT date(timestamp / 1000, 'unixepoch', 'localtime') AS d
            FROM dns_log
            ORDER BY d DESC
            """.trimIndent(),
            null
        )
        val dates = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) dates.add(it.getString(0))
        }
        return dates
    }

    /** 특정 날짜(yyyy-MM-dd)의 로그를 최신순으로 반환 */
    fun getEntriesForDate(context: Context, date: String): List<DnsLogEntry> {
        val db = getHelper(context).readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT timestamp, app_package, domain, dns_server,
                   cell_type, mcc, mnc, cell_id, area_code, pci, signal_dbm
            FROM dns_log
            WHERE date(timestamp / 1000, 'unixepoch', 'localtime') = ?
            ORDER BY timestamp DESC
            """.trimIndent(),
            arrayOf(date)
        )
        val list = mutableListOf<DnsLogEntry>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    DnsLogEntry(
                        timestamp = it.getLong(0),
                        appPackage = it.getString(1) ?: "unknown",
                        domain = it.getString(2) ?: "",
                        dnsServer = it.getString(3) ?: "",
                        cellNetworkType = it.getString(4),
                        mcc = it.getString(5),
                        mnc = it.getString(6),
                        cellId = if (it.isNull(7)) null else it.getLong(7),
                        areaCode = if (it.isNull(8)) null else it.getInt(8),
                        pci = if (it.isNull(9)) null else it.getInt(9),
                        signalDbm = if (it.isNull(10)) null else it.getInt(10)
                    )
                )
            }
        }
        return list
    }

    fun exportCsv(context: Context, outFile: File) {
        val db = getHelper(context).readableDatabase
        val cursor = db.rawQuery("SELECT * FROM dns_log ORDER BY timestamp DESC", null)
        FileWriter(outFile).use { writer ->
            writer.append("timestamp,app_package,domain,dns_server,cell_type,mcc,mnc,cell_id,area_code,pci,signal_dbm\n")
            cursor.use {
                while (it.moveToNext()) {
                    val row = (0 until it.columnCount).joinToString(",") { idx ->
                        it.getString(idx)?.replace(",", " ") ?: ""
                    }
                    writer.append(row).append("\n")
                }
            }
        }
    }

    private class DbHelper(context: Context) :
        SQLiteOpenHelper(context, "traffic_logger.db", null, 2) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE dns_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    app_package TEXT,
                    domain TEXT,
                    dns_server TEXT,
                    cell_type TEXT,
                    mcc TEXT,
                    mnc TEXT,
                    cell_id INTEGER,
                    area_code INTEGER,
                    pci INTEGER,
                    signal_dbm INTEGER
                )
                """.trimIndent()
            )
            createRiskAlertTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 기존 로그(dns_log)는 절대 지우지 않고, 신규 테이블만 없으면 추가한다.
            if (oldVersion < 2) {
                createRiskAlertTable(db)
            }
        }

        private fun createRiskAlertTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS risk_alert_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    app_package TEXT,
                    target TEXT,
                    category TEXT,
                    reason TEXT
                )
                """.trimIndent()
            )
        }
    }
}

/** 위험으로 판정되어 알림이 발생한 이벤트 하나 */
data class RiskAlertEntry(
    val timestamp: Long,
    val appPackage: String,
    val target: String,   // 도메인 또는 관련 대상
    val category: String, // "위험앱_DNS조회" / "타이포스쿼팅" / "IP직통통신의심" 등
    val reason: String     // 사람이 읽을 상세 설명
)
