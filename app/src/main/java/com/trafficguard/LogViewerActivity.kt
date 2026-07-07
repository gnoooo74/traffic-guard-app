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

    private var dates: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        dateSpinner = findViewById(R.id.dateSpinner)
        logListView = findViewById(R.id.logListView)
        emptyText = findViewById(R.id.emptyText)

        loadDatesAndShowLatest()
    }

    override fun onResume() {
        super.onResume()
        // 화면 다시 볼 때마다 최신 상태로 갱신 (백그라운드에서 계속 로그가 쌓이므로)
        loadDatesAndShowLatest()
    }

    private fun loadDatesAndShowLatest() {
        dates = LogStore.getAvailableDates(this)

        if (dates.isEmpty()) {
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
                showLogsForDate(dates[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 아무것도 선택 안 한 초기 상태 = 첫 번째(최신) 날짜를 자동으로 보여줌
        dateSpinner.setSelection(0)
        showLogsForDate(dates[0])
    }

    private fun showLogsForDate(date: String) {
        val entries = LogStore.getEntriesForDate(this, date)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val rows = entries.map { e ->
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

        logListView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            rows.ifEmpty { listOf("이 날짜엔 기록된 로그가 없습니다") }
        )
    }

    private fun formatDateLabel(date: String): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return if (date == today) "$date (오늘, 최신)" else date
    }
}
