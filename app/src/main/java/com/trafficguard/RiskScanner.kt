package com.trafficguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class AppRiskReport(
    val packageName: String,
    val appName: String,
    val riskScore: Int,          // 높을수록 위험
    val reasons: List<String>,
    val installerPackage: String?,
    val isSystemApp: Boolean
)

/**
 * 코드 실행/네트워크 캡처 없이, 순수 권한/설치정보 조합만으로
 * RAT/스파이웨어 전형 패턴을 스코어링한다.
 * 가장 빠르고 오탐도 적은 1차 스크리닝 레이어.
 */
class RiskScanner(private val context: Context) {

    // RAT/스토킹웨어가 실제로 자주 요구하는 위험 권한들
    private val highRiskPermissions = setOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.SYSTEM_ALERT_WINDOW",       // 다른 앱 위에 표시
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.READ_CALL_LOG",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.REQUEST_INSTALL_PACKAGES"
    )

    fun scanAll(): List<AppRiskReport> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return apps.mapNotNull { appInfo ->
            try {
                scanOne(appInfo)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.riskScore }
    }

    private fun scanOne(appInfo: ApplicationInfo): AppRiskReport {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            appInfo.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val grantedPermissions = pkgInfo.requestedPermissions?.toList() ?: emptyList()

        var score = 0
        val reasons = mutableListOf<String>()

        val hasAccessibility = grantedPermissions.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")
        val hasOverlay = grantedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")
        val hasInternet = grantedPermissions.contains("android.permission.INTERNET")

        // RAT 전형 패턴: 접근성 + 오버레이 + 인터넷 동시 보유
        if (hasAccessibility && hasOverlay && hasInternet) {
            score += 50
            reasons.add("접근성+오버레이+인터넷 권한 동시 보유 (원격제어 전형 패턴)")
        }

        if (hasAccessibility && hasInternet) {
            score += 20
            reasons.add("접근성 서비스 + 인터넷 권한 (화면 내용 외부 전송 가능)")
        }

        val sensitiveGranted = grantedPermissions.filter { it in highRiskPermissions }
        if (sensitiveGranted.isNotEmpty()) {
            score += sensitiveGranted.size * 5
            reasons.add("민감 권한 보유: ${sensitiveGranted.joinToString(", ")}")
        }

        // 평문 HTTP(비암호화) 통신이 허용된 앱인지 확인.
        // Android 9(API 28)부터는 앱이 명시적으로 usesCleartextTraffic=true를 선언하거나
        // 구버전 targetSdk(<28)라 기본 허용 상태가 아니면 평문 통신 자체가 시스템에서 막힘.
        // 즉 이 플래그가 true라는 건 "이 앱은 암호화 안 된 통신이 가능하다"는 정적 신호.
        @Suppress("DEPRECATION")
        val allowsCleartext = (appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
        if (allowsCleartext && !isSystemApp) {
            score += 10
            reasons.add("평문(HTTP) 통신 허용됨 — 대부분의 정상 서비스는 HTTPS만 사용하므로 잠재적 위험 신호")
        }

        // 설치 출처 확인 (사이드로딩 여부)
        val installer = try {
            pm.getInstallSourceInfo(appInfo.packageName).installingPackageName
        } catch (e: Exception) {
            null
        }
        val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        if (!isSystemApp && installer == null) {
            score += 30
            reasons.add("설치 출처 불명 (Play스토어 등 공식 경로 아님, 사이드로딩 가능성)")
        }

        // 앱 이름이 시스템 앱을 위장하는 흔한 패턴
        val appName = pm.getApplicationLabel(appInfo).toString()
        val suspiciousNames = listOf("system update", "시스템 업데이트", "cleaner", "booster", "클리너")
        if (!isSystemApp && suspiciousNames.any { appName.contains(it, ignoreCase = true) }) {
            score += 15
            reasons.add("시스템 앱을 위장한 이름 패턴")
        }

        return AppRiskReport(
            packageName = appInfo.packageName,
            appName = appName,
            riskScore = score,
            reasons = reasons,
            installerPackage = installer,
            isSystemApp = isSystemApp
        )
    }
}
