package com.trafficguard

import android.content.Context
import android.net.TrafficStats
import android.os.Process

/**
 * VPN 라우팅을 전혀 건드리지 않고, "데이터는 오갔는데 DNS 조회 기록이 없는 앱"을
 * 주기적으로 찾아내는 간접 탐지기.
 *
 * TrafficStats.getUidTxBytes/RxBytes는 특별한 권한 없이 호출 가능하며,
 * 전체 트래픽 캡처(0.0.0.0/0 라우팅)를 하지 않으므로 인터넷 안정성에 영향이 전혀 없다.
 *
 * 한계: "도메인 없이 IP 직통 통신"의 확정 증거는 아니고, 정황 추정(heuristic)이다.
 * 예를 들어 DNS 결과가 시스템 DNS 캐시에 이미 있어서 새로 조회를 안 한 경우도
 * 오탐으로 잡힐 수 있다.
 */
class DirectIpTrafficMonitor(private val context: Context) {

    private val lastTx = mutableMapOf<Int, Long>()
    private val lastRx = mutableMapOf<Int, Long>()

    private val significantBytesThreshold = 50_000L // 이 이상 오가야 "활동 있음"으로 간주

    /**
     * 주기적으로 이 함수를 호출한다 (예: 2분마다).
     * intervalStartMs ~ 지금까지의 DNS 로그와 비교해서, 데이터는 오갔는데
     * DNS 조회가 없는 앱을 찾아 콜백으로 알려준다.
     */
    fun checkForDirectIpActivity(
        intervalStartMs: Long,
        onSuspicious: (packageName: String, txDelta: Long, rxDelta: Long) -> Unit
    ) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val now = System.currentTimeMillis()

        // 이 시간대에 DNS 조회 기록이 있었던 패키지 목록 (LogStore에서 조회)
        val packagesWithDnsActivity = LogStore.getPackagesWithActivitySince(context, intervalStartMs)

        for (appInfo in apps) {
            val uid = appInfo.uid
            if (uid < Process.FIRST_APPLICATION_UID) continue // 시스템 UID 제외

            val tx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: continue
            val rx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: continue

            val prevTx = lastTx[uid] ?: tx
            val prevRx = lastRx[uid] ?: rx
            val txDelta = tx - prevTx
            val rxDelta = rx - prevRx

            lastTx[uid] = tx
            lastRx[uid] = rx

            val totalDelta = txDelta + rxDelta
            if (totalDelta < significantBytesThreshold) continue // 활동 미미하면 무시

            if (appInfo.packageName !in packagesWithDnsActivity) {
                onSuspicious(appInfo.packageName, txDelta, rxDelta)
            }
        }
    }
}
