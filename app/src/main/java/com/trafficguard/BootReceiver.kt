package com.trafficguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // VpnService는 부팅 시점에 사용자 동의(prepare) 없이도,
            // 이미 한 번 허용된 적 있으면 자동으로 시작 가능
            if (VpnService.prepare(context) == null) {
                // API 26+에서는 백그라운드에서 startService() 대신
                // startForegroundService()를 써야 함 (서비스가 곧바로 startForeground 호출)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DnsVpnService::class.java)
                )
            }
            // prepare()가 null이 아니면(한 번도 허용 안 한 상태) 자동 시작 불가 ->
            // 이 경우는 사용자가 앱을 최초 1회는 직접 열어서 VPN 허용을 해줘야 함
        }
    }
}
