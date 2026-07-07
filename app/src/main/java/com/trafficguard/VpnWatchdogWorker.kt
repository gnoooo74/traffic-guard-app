package com.trafficguard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * ip-logger-app이 WorkManager 하나로 재부팅에도 끄떡없이 살아남던 것과 같은 원리를
 * DnsVpnService(VPN 서비스)에도 보험으로 적용한 것.
 *
 * BootReceiver(BOOT_COMPLETED)는 "한 번의 이벤트"에만 의존하기 때문에,
 * 브로드캐스트 자체가 씹히거나(제조사 배터리 관리) 서비스가 중간에 조용히 죽어버리면
 * 다음 재부팅 전까지 복구가 안 된다. WorkManager 주기 작업은 그와 별개로
 * OS가 스스로 재등록/재실행을 보장해주므로, 15분마다 한 번씩 독립적으로
 * "VPN 권한이 아직 유효한지 / 서비스가 살아있는지"를 재확인해서 자가 치유한다.
 */
class VpnWatchdogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prepareIntent = VpnService.prepare(ctx)

        if (prepareIntent != null) {
            // VPN 동의가 어느 시점엔가 회수된 상태 -> 사용자가 직접 재승인해야 함
            AlertNotifier.notifyActionRequired(
                ctx,
                "⚠ TrafficGuard 감시 중지됨",
                "VPN 권한이 취소되어 DNS 감시가 꺼져 있습니다. 탭해서 앱을 열고 다시 허용해주세요."
            )
            return Result.success()
        }

        if (!DnsVpnService.isRunning) {
            // 권한은 있는데 서비스가 죽어있는 상태 -> 조용히 재시작 시도
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, DnsVpnService::class.java)
                )
            } catch (e: Exception) {
                // 재시작도 실패하면 사용자에게 알림
                AlertNotifier.notifyActionRequired(
                    ctx,
                    "⚠ TrafficGuard 감시 중지됨",
                    "DNS 감시 서비스가 중단되어 자동 재시작에 실패했습니다. 앱을 열어 확인해주세요."
                )
            }
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "vpn_watchdog"

        /** MainActivity, BootReceiver 양쪽에서 호출해도 안전 (KEEP 정책 -> 중복 등록 안 됨) */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<VpnWatchdogWorker>(
                15, TimeUnit.MINUTES // WorkManager 최소 주기가 15분
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
