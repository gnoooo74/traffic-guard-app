package com.trafficguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileWriter

/**
 * DNS 로그 저장/조회/CSV export.
 * 기존 iplogger가 이미 저장소를 갖고 있다면 이 파일 대신
 * 그 저장소 스키마에 cell_* 컬럼만 추가하는 편이 낫다.
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

    /** 로그가 존재하는 날짜 목록을 최신순으로 반환 (yyyy-MM-dd) */
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

    /** 특정 도메인이 처음 관측된 시각 이후, 동일 앱의 데이터 사용량 급증 여부 등 확장 분석은
     *  이 테이블을 쿼리해서 TrafficAnomalyMonitor와 조인하는 식으로 확장 가능 */

    private class DbHelper(context: Context) :
        SQLiteOpenHelper(context, "traffic_logger.db", null, 1) {

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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS dns_log")
            onCreate(db)
        }
    }
}
