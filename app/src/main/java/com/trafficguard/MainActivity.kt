package com.trafficguard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
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

        // 1) 위치 권한 요청 (셀 정보 조회 필수)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            1001
        )

        // 2) 정적 위험도 스캔 먼저 실행 (즉시 결과 확인 가능)
        val riskReports = RiskScanner(this).scanAll()
        val highRisk = riskReports.filter { it.riskScore >= 50 }
        // TODO: highRisk를 리스트뷰/RecyclerView에 표시
        // 예: highRisk.forEach { Log.w("RISK", "${it.appName}: ${it.riskScore} - ${it.reasons}") }

        // 3) DNS 감시 VPN 시작 (사용자가 버튼 눌렀을 때 호출하는 걸 권장)
        startDnsMonitoring()
    }

    private fun startDnsMonitoring() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            // 시스템이 "이 앱의 VPN 연결을 허용하시겠습니까?" 팝업을 띄움
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            // 이미 허용된 상태
            startService(Intent(this, DnsVpnService::class.java))
        }
    }

    private fun stopDnsMonitoring() {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        }
        startService(intent)
    }
}
