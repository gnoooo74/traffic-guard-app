package com.trafficguard

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class LogViewerActivity : AppCompatActivity() {

    private lateinit var dateSpinner: Spinner
    private lateinit var logListView: ListView
    private lateinit var emptyText: TextView
    private lateinit var tabLogButton: Button
    private lateinit var tabAnalysisButton: Button

    private var dates: List<String> = emptyList()

    // false = 전체 로그 탭, true = 위험 분석 탭
    private var showAnalysisTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        dateSpinner = findViewById(R.id.dateSpinner)
        logListView = findViewById(R.id.logListView)
        emptyText = findViewById(R.id.emptyText)
        tabLogButton = findViewById(R.id.tabLogButton)
        tabAnalysisButton = findViewById(R.id.tabAnalysisButton)

        tabLogButton.setOnClickListener {
            showAnalysisTab = false
            updateTabAppearance()
            loadDatesAndShowLatest()
        }
        tabAnalysisButton.setOnClickListener {
            showAnalysisTab = true
            updateTabAppearance()
            loadDatesAndShowLatest()
        }

        updateTabAppearance()
        loadDatesAndShowLatest()
    }

    override fun onResume() {
        super.onResume()
        // 화면 다시 볼 때마다 최신 상태로 갱신 (백그라운드에서 계속 로그가 쌓이므로)
        loadDatesAndShowLatest()
    }

    private fun updateTabAppearance() {
        tabLogButton.alpha = if (showAnalysisTab) 0.5f else 1.0f
        tabAnalysisButton.alpha = if (showAnalysisTab) 1.0f else 0.5f
    }

    private fun loadDatesAndShowLatest() {
        dates = if (showAnalysisTab) {
            LogStore.getAlertDates(this)
        } else {
            LogStore.getAvailableDates(this)
        }

        if (dates.isEmpty()) {
            emptyText.text = if (showAnalysisTab) {
                "위험으로 판정된 기록이 없습니다 (정상 상태입니다)"
            } else {
                "저장된 로그가 없습니다"
            }
            emptyText.visibility = View.VISIBLE
            logListView.visibility = View.GONE
            dateSpinner.adapter = null
            return
        }

        emptyText.visibility = View.GONE
        logListView.visibility = View.VISIBLE

        val labels = dates.map { formatDateLabel(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        dateSpinner.adapter = adapter

        dateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showEntriesForDate(dates[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 아무것도 선택 안 한 초기 상태 = 첫 번째(최신) 날짜를 자동으로 보여줌
        dateSpinner.setSelection(0)
        showEntriesForDate(dates[0])
    }

    private fun showEntriesForDate(date: String) {
        val rows = if (showAnalysisTab) {
            buildAnalysisRows(date)
        } else {
            buildLogRows(date)
        }

        logListView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            rows.ifEmpty { listOf("이 날짜엔 기록된 항목이 없습니다") }
        )
    }

    private fun buildLogRows(date: String): List<String> {
        val entries = LogStore.getEntriesForDate(this, date)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        return entries.map { e ->
            val time = timeFormat.format(Date(e.timestamp))
            if (e.appPackage == "SYSTEM_HEARTBEAT") {
                "$time  •  (서비스 생존 확인)"
            } else {
                val cellInfo = if (e.cellId != null) {
                    "  |  ${e.cellNetworkType ?: "-"} CellID:${e.cellId} LAC/TAC:${e.areaCode ?: "-"} PCI:${e.pci ?: "-"}"
                } else ""
                "$time  •  ${e.appPackage}\n     → ${e.domain}$cellInfo"
            }
        }
    }

    private fun buildAnalysisRows(date: String): List<String> {
        val alerts = LogStore.getAlertsForDate(this, date)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        return alerts.map { a ->
            val time = timeFormat.format(Date(a.timestamp))
            "⚠ $time  •  [${a.category}]\n" +
                "     앱: ${a.appPackage}\n" +
                "     대상: ${a.target}\n" +
                "     사유: ${a.reason}"
        }
    }

    private fun formatDateLabel(date: String): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return if (date == today) "$date (오늘, 최신)" else date
    }
}
