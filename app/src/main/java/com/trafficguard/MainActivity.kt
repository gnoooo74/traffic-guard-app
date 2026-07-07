package com.trafficguard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startService(Intent(this, DnsVpnService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1) 위치 권한 + 알림 권한 요청 (셀 정보 조회, 위험 알림 표시에 필수)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.POST_NOTIFICATIONS
            ),
            1001
        )

        // 2) 정적 위험도 스캔 (필요 시 결과를 별도 화면에서 보여주도록 확장 가능)
        RiskScanner(this).scanAll()

        // 3) DNS 감시 VPN 시작
        startDnsMonitoring()

        // 4) 로그 보기 버튼
        findViewById<Button>(R.id.viewLogButton).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
    }

    private fun startDnsMonitoring() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            startService(Intent(this, DnsVpnService::class.java))
        }
    }
}
