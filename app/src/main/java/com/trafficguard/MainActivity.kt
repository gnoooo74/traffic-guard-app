package com.trafficguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
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

        // 1-1) 사용 정보 접근 권한 안내 (선택) - 로그에 "화면에 떠 있던 앱"을 표시하기 위함.
        //      권한이 없어도 포어/백그라운드 상태 태그 자체는 동작하므로 강제하지 않는다.
        maybePromptUsageAccess()

        // 2) 정적 위험도 스캔 (필요 시 결과를 별도 화면에서 보여주도록 확장 가능)
        RiskScanner(this).scanAll()

        // 3) DNS 감시 VPN 시작
        startDnsMonitoring()

        // 3-1) 재부팅/서비스 중단에 대비한 워치독 등록 (ip-logger와 동일한 원리)
        VpnWatchdogWorker.schedule(this)

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

    /**
     * "사용 정보 접근" 권한이 없으면, 한 번 안내 다이얼로그를 띄우고 설정 화면으로 보낸다.
     * 이 권한이 있으면 로그에 "그 순간 화면에 떠 있던 앱"이 함께 기록되어,
     * 백그라운드에서 몰래 통신한 앱을 더 정확히 구별할 수 있다.
     */
    private fun maybePromptUsageAccess() {
        if (AppStateResolver.hasUsageAccess(this)) return

        AlertDialog.Builder(this)
            .setTitle("사용 정보 접근 권한 (선택)")
            .setMessage(
                "이 권한을 켜면, 로그에 '그 순간 화면에 떠 있던 앱'이 함께 기록됩니다.\n\n" +
                "이를 통해 사용자가 보고 있던 앱과, 뒤에서 몰래 통신하는 앱을 더 정확히 구별할 수 있습니다.\n\n" +
                "권한 없이도 포어/백그라운드 상태 표시는 동작합니다."
            )
            .setPositiveButton("설정 열기") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("나중에", null)
            .show()
    }
}
