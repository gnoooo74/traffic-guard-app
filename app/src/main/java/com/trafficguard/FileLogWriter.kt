package com.trafficguard

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * SQLite(LogStore)와는 별개로, 사람이 다운로드 폴더에서 바로 꺼내볼 수 있는
 * 날짜별 CSV 파일을 남긴다. (Downloads/net_logs/net_log_yyyy-MM-dd.csv)
 *
 * Android 10+ 스코프드 스토리지 정책상 MediaStore API를 사용하며,
 * 앱이 직접 만든 파일이라 별도의 저장소 권한이 필요 없다.
 */
object FileLogWriter {
    private const val RELATIVE_PATH = "Download/net_logs/"
    private const val HEADER =
        "time,app_package,domain,dns_server,cell_type,mcc,mnc,cell_id,area_code,pci,signal_dbm\n"

    private const val ALERT_RELATIVE_PATH = "Download/net_logs/"
    private const val ALERT_HEADER = "time,app_package,category,target,reason\n"

    fun appendLine(context: Context, date: String, csvLine: String) {
        try {
            val resolver = context.contentResolver
            val fileName = "net_log_$date.csv"

            var uri = findExistingUri(context, fileName, RELATIVE_PATH)
            var isNewFile = false

            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                }
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                isNewFile = true
            }

            uri ?: return

            resolver.openOutputStream(uri, "wa")?.use { out ->
                if (isNewFile) out.write(HEADER.toByteArray())
                out.write((csvLine + "\n").toByteArray())
            }
        } catch (e: Exception) {
            // 파일 로깅 실패는 핵심 기능(SQLite 로깅)에 영향 주면 안 되므로 조용히 무시
        }
    }

    /**
     * 위험 판정(경고) 이력을 ip-logger 방식처럼 하루 한 파일에 그대로 누적한다.
     * (Downloads/net_logs/risk_alert_yyyy-MM-dd.csv)
     * 기존 DB(risk_alert_log)와 별개로, 앱 없이도 바로 꺼내볼 수 있는 사본을 남기는 목적.
     */
    fun appendRiskAlertLine(context: Context, date: String, csvLine: String) {
        try {
            val resolver = context.contentResolver
            val fileName = "risk_alert_$date.csv"

            var uri = findExistingUri(context, fileName, ALERT_RELATIVE_PATH)
            var isNewFile = false

            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, ALERT_RELATIVE_PATH)
                }
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                isNewFile = true
            }

            uri ?: return

            resolver.openOutputStream(uri, "wa")?.use { out ->
                if (isNewFile) out.write(ALERT_HEADER.toByteArray())
                out.write((csvLine + "\n").toByteArray())
            }
        } catch (e: Exception) {
            // 조용히 무시 (DB 기록이 핵심, 파일은 보조 사본)
        }
    }

    private fun findExistingUri(context: Context, fileName: String, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, relativePath)

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }
}
