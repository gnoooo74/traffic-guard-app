package com.trafficguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive 호출됨: action=${intent.action}")
        diag(context, "리시버_호출됨", "action=${intent.action}")

        val relevantAction = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!relevantAction) return

        // WorkManager 워치독도 함께 재등록 (KEEP 정책이라 이미 있으면 중복 안 됨)
        VpnWatchdogWorker.schedule(context)

        // VpnService는 부팅 시점에 사용자 동의(prepare) 없이도,
        // 이미 한 번 허용된 적 있으면 자동으로 시작 가능
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent == null) {
            Log.d(TAG, "VPN 권한 승인됨 -> DnsVpnService 자동 시작 시도")
            try {
                // API 26+에서는 백그라운드에서 startService() 대신
                // startForegroundService()를 써야 함 (서비스가 곧바로 startForeground 호출)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DnsVpnService::class.java)
                )
                Log.d(TAG, "startForegroundService 호출 완료")
                diag(context, "자동시작_시도완료", "startForegroundService 정상 호출됨")
            } catch (e: Exception) {
                // 제조사별 백그라운드 시작 제한 등으로 예외가 날 경우 원인 파악용
                Log.e(TAG, "DnsVpnService 자동 시작 실패: ${e.javaClass.simpleName} ${e.message}", e)
                diag(context, "자동시작_실패", "${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            // prepare()가 null이 아니면(한 번도 허용 안 한 상태) 자동 시작 불가 ->
            // 이 경우는 사용자가 앱을 최초 1회는 직접 열어서 VPN 허용을 해줘야 함
            Log.w(TAG, "VPN 권한 미승인 상태 -> 자동 시작 불가. 앱을 한 번 직접 열어 VPN 허용 필요")
            diag(context, "VPN권한_미승인", "앱을 직접 열어 VPN 허용을 다시 승인해야 함")
            AlertNotifier.notifyActionRequired(
                context,
                "⚠ TrafficGuard 감시 중지됨",
                "재부팅 후 DNS 감시가 자동으로 시작되지 못했습니다. 탭해서 앱을 열고 VPN 권한을 다시 허용해주세요."
            )
        }
    }

    /**
     * adb 없이도 앱의 "로그 보기" 화면(위험 판정 이력)에서 바로 확인할 수 있도록
     * 부팅 시점의 진단 결과를 risk_alert_log 테이블에 남긴다.
     */
    private fun diag(context: Context, category: String, reason: String) {
        try {
            LogStore.insertRiskAlert(
                context,
                RiskAlertEntry(
                    timestamp = System.currentTimeMillis(),
                    appPackage = "BOOT진단",
                    target = "(부팅감지)",
                    category = category,
                    reason = reason
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "diag 기록 실패: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
