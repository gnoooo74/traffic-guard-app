package com.trafficguard

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * DNS 조회를 발생시킨 앱이 "그 순간 어떤 상태였는지(포어/백그라운드)"와,
 * "그 순간 화면에 실제로 떠 있던 앱이 무엇인지"를 판정한다.
 *
 * 두 가지 정보원을 조합한다:
 *  1) ActivityManager.getRunningAppProcesses() 의 importance
 *     -> 네트워크를 실제로 쓴 그 UID 앱의 상태(FOREGROUND / BACKGROUND_... 등)
 *  2) UsageStatsManager (사용 정보 접근 권한 필요)
 *     -> 그 시각 화면 맨 앞에 있던 앱(사용자가 보고 있던 앱)
 *
 * 판단 방법:
 *  - 로그 줄의 주인공 = 네트워크를 실제로 쓴 앱(UID) -> importanceLabel 로 상태 표시
 *  - 화면 앱(foregroundAppLabel) = 옆에 붙는 정황 정보
 *  - 둘이 같으면: 사용자가 직접 쓰던 앱이 통신 (정상 정황)
 *  - 둘이 다르면: 화면 앱과 별개로 뒤에서 다른 앱이 통신 (백그라운드 활동, 주의 대상)
 */
object AppStateResolver {

    /**
     * 네트워크를 쓴 앱(UID)의 상태를 아래 이름으로 반환한다.
     * (괄호 안에는 판정 불가/특수 케이스의 상세 원인을 덧붙인다)
     *   FOREGROUND          - IMPORTANCE_FOREGROUND (화면에 떠 있음)
     *   FOREGROUND_SERVICE  - IMPORTANCE_FOREGROUND_SERVICE (음악 재생 등 포그라운드 서비스)
     *   FOREGROUND_PARTIAL  - IMPORTANCE_VISIBLE / IMPORTANCE_PERCEPTIBLE (부분적으로 보임)
     *   BACKGROUND_SERVICE  - IMPORTANCE_SERVICE (백그라운드 서비스)
     *   BACKGROUND_CACHED   - IMPORTANCE_CACHED/BACKGROUND (캐시된 백그라운드)
     *   UNKNOWN(원인)        - 위 어디에도 안 잡히거나 프로세스 정보를 못 구함
     */
    fun resolveImportanceLabel(context: Context, uid: Int, packageName: String?): String {
        if (uid <= 0) return "UNKNOWN(NO_UID)"
        if (packageName == null) return "UNKNOWN(NO_PACKAGE)"

        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses
                ?: return "UNKNOWN(NO_PROCESS_LIST)"

            // 해당 패키지를 담고 있는 프로세스를 찾는다
            val proc = processes.firstOrNull { p ->
                p.pkgList?.contains(packageName) == true
            } ?: return "UNKNOWN(PROCESS_NOT_FOUND)"

            mapImportance(proc.importance)
        } catch (e: Exception) {
            "UNKNOWN(${e.javaClass.simpleName})"
        }
    }

    private fun mapImportance(importance: Int): String {
        return when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
                "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE ->
                "FOREGROUND_PARTIAL"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ->
                "BACKGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED ->
                "BACKGROUND_CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE ->
                "UNKNOWN(IMPORTANCE_GONE)"
            else ->
                "UNKNOWN(IMPORTANCE_$importance)"
        }
    }

    /**
     * "사용 정보 접근" 권한이 켜져 있는지 확인.
     * 이 권한이 있어야 UsageStatsManager로 화면에 떠 있던 앱을 조회할 수 있다.
     */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * nowMs 시점에 화면 맨 앞에 있던 앱의 사람이 읽을 수 있는 이름을 반환한다.
     * 권한이 없거나 못 찾으면 null.
     */
    fun resolveForegroundAppLabel(context: Context, nowMs: Long): String? {
        val pkg = resolveForegroundPackage(context, nowMs) ?: return null
        return toAppLabel(context, pkg)
    }

    /**
     * nowMs 시점에 화면 맨 앞에 있던 앱의 "패키지명"을 반환한다.
     * 통신 앱 패키지명과 정확히 비교하기 위해 이름(label) 대신 패키지명을 쓴다.
     * 권한이 없거나 못 찾으면 null.
     */
    fun resolveForegroundPackage(context: Context, nowMs: Long): String? {
        if (!hasUsageAccess(context)) return null

        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            // 최근 30초 구간의 이벤트를 훑어, 가장 마지막으로 화면에 올라온 앱을 찾는다.
            // (10초는 이벤트가 하나도 안 잡히는 경우가 잦아 30초로 넉넉히 잡음)
            val events = usm.queryEvents(nowMs - 30_000, nowMs)
            val event = android.app.usage.UsageEvents.Event()
            var lastForegroundPkg: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundPkg = event.packageName
                }
            }

            lastForegroundPkg
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 상태 판정 + 화면 앱 라벨을 한 번의 UsageStats 조회로 함께 구한다.
     * (DNS 조회마다 UsageStats를 두 번 쿼리하지 않도록 통합)
     *
     * @return Pair(상태라벨, 화면앱라벨)
     */
    fun resolveStateAndForeground(
        context: Context,
        uid: Int,
        commPackage: String?,
        nowMs: Long
    ): Pair<String, String?> {
        val foregroundPkg = resolveForegroundPackage(context, nowMs)
        val foregroundLabel = foregroundPkg?.let { toAppLabel(context, it) }

        val state = when {
            commPackage == null -> "UNKNOWN(NO_PACKAGE)"
            foregroundPkg != null && foregroundPkg == commPackage -> "FOREGROUND"
            foregroundPkg != null -> "BACKGROUND"
            // 화면 앱을 못 구함(권한 없음 등) -> importance 보조 판정
            else -> resolveImportanceLabel(context, uid, commPackage)
        }

        return Pair(state, foregroundLabel)
    }

    /**
     * 최종 상태 판정. 통신 앱(commPackage)이 그 순간 포어그라운드였는지 백그라운드였는지 결정한다.
     *
     * 판정 우선순위:
     *  1) UsageStats로 얻은 "화면 앱"과 통신 앱을 비교 (가장 신뢰도 높음)
     *     - 같으면 FOREGROUND, 다르면 BACKGROUND
     *  2) 화면 앱을 못 구하면(권한 없음 등) importance 기반 보조 판정으로 폴백
     *
     * getRunningAppProcesses()의 importance는 다른 앱에 대해 대부분
     * PROCESS_NOT_FOUND를 반환하므로 주 기준으로 쓰지 않고 폴백으로만 쓴다.
     */
    fun resolveState(context: Context, uid: Int, commPackage: String?, nowMs: Long): String {
        if (commPackage == null) return "UNKNOWN(NO_PACKAGE)"

        val foregroundPkg = resolveForegroundPackage(context, nowMs)

        if (foregroundPkg != null) {
            // 화면 앱을 알아냈다 -> 통신 앱과 비교하는 게 가장 정확
            return if (foregroundPkg == commPackage) {
                "FOREGROUND"
            } else {
                "BACKGROUND"
            }
        }

        // 화면 앱을 못 구한 경우(사용 정보 접근 권한 없음 등) -> importance 보조 판정
        return resolveImportanceLabel(context, uid, commPackage)
    }

    fun toAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        } catch (e: Exception) {
            packageName
        }
    }
}
