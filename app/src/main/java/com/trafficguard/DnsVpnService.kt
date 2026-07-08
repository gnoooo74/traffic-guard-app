package com.trafficguard

import android.app.*
import android.content.pm.PackageManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * DNS 서버로 향하는 UDP:53 트래픽만 tun으로 좁게 라우팅해서
 * "어떤 앱이 어떤 도메인을 조회하는지" 실시간 기록한다.
 * 다른 모든 트래픽은 평소처럼 정상 라우팅되므로 인터넷은 끊기지 않는다.
 */
class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    // 같은 (카테고리+앱+대상) 조합은 쿨다운 시간 안에는 알림을 중복으로 띄우지 않음
    // (A/AAAA 레코드 동시 조회, 재시도 등으로 짧은 시간에 같은 판정이 여러 번 나오는 걸 방지)
    private val lastAlertTime = mutableMapOf<String, Long>()
    private val alertCooldownMs = 60 * 1000L // 1분

    private lateinit var cellInfoLogger: CellInfoLogger
    private lateinit var riskEvaluator: RiskEvaluator
    private lateinit var directIpMonitor: DirectIpTrafficMonitor

    companion object {
        const val CHANNEL_ID = "dns_monitor_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.trafficguard.STOP"

        // 워치독(WorkManager)이 서비스 생사를 판단하는 용도. 프로세스가 죽으면
        // 자동으로 false가 되므로(정적 필드가 초기화됨), 별도 리셋 로직 불필요.
        @Volatile var isRunning: Boolean = false
    }

    /** 같은 키(카테고리+앱+대상)로 최근 쿨다운 시간 안에 이미 알림을 보냈는지 확인 */
    private fun shouldAlert(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAlertTime[key]
        if (last != null && now - last < alertCooldownMs) return false
        lastAlertTime[key] = now
        return true
    }

    override fun onCreate() {
        super.onCreate()
        cellInfoLogger = CellInfoLogger(this)
        directIpMonitor = DirectIpTrafficMonitor(this)

        // 위험도 스캔은 서비스 시작 시 한 번만 계산해서 캐시 (실시간 DNS 판단마다 재계산하면 무거움)
        val riskCache = RiskScanner(this).scanAll().associate { it.packageName to it.riskScore }
        riskEvaluator = RiskEvaluator(riskCache)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startVpn()
        isRunning = true
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DNS 감시", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DNS 트래픽 감시 중")
            .setContentText("앱별 도메인 조회 기록 중")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (running) return

        val cm = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = cm.activeNetwork
        val linkProps = activeNetwork?.let { cm.getLinkProperties(it) }

        // IPv6 DNS 서버는 addRoute(/32)와 프리픽스 길이가 안 맞아 크래시 나므로 IPv4만 사용
        val allDns = linkProps?.dnsServers ?: emptyList()
        val dnsServers = allDns.filterIsInstance<java.net.Inet4Address>()
            .ifEmpty { listOf(InetAddress.getByName("8.8.8.8")) }

        val builder = Builder()
            .setSession("TrafficLoggerDNS")
            .addAddress("10.0.0.2", 32)
            .addDnsServer(dnsServers.first())

        // DNS 서버 IP만 좁게 라우팅 (전체 트래픽 X -> 인터넷 안 끊김)
        for (dns in dnsServers) {
            builder.addRoute(dns.hostAddress, 32)
        }

        vpnInterface = builder.establish() ?: return
        running = true

        executor.execute { runLoop(dnsServers) }
    }

    private fun runLoop(dnsServers: List<InetAddress>) {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        // input.read()는 패킷이 올 때까지 블로킹되므로,
        // 하트비트는 별도 스레드에서 독립적으로 남긴다 (트래픽이 없어도 생존 확인 가능)
        startHeartbeatThread()
        startDirectIpMonitorThread()

        while (running) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (length <= 0) continue

            val udp = DnsPacketUtils.parseUdp(buffer, length) ?: continue
            if (udp.dstPort != 53) continue // DNS 질의만 처리

            val queryName = DnsPacketUtils.extractQueryName(udp.payload) ?: continue

            // 어느 앱(UID)이 이 질의를 보냈는지 역추적
            val (appPackage, appUid) = resolveOwnerPackage(udp.srcIp.hostAddress, udp.srcPort, udp.dstIp.hostAddress, 53)

            // 실제 DNS 서버로 질의를 그대로 전달 (protect()로 VPN 루프 방지)
            val responsePayload = forwardDnsQuery(udp.payload, udp.dstIp)

            // 로그 기록 (셀 정보 + 앱 상태 결합)
            logDnsQuery(appPackage, appUid, queryName, udp.dstIp.hostAddress)

            if (responsePayload != null) {
                val responsePacket = DnsPacketUtils.buildResponsePacket(
                    originalSrcIp = udp.srcIp.address,
                    originalDstIp = udp.dstIp.address,
                    srcPort = 53,
                    dstPort = udp.srcPort,
                    dnsResponsePayload = responsePayload
                )
                try {
                    output.write(responsePacket)
                } catch (e: Exception) {
                    // 무시하고 다음 패킷 계속 처리
                }
            }
        }
    }

    /** 실제 DNS 서버에 질의를 전달하고 응답 바이트를 받아온다 */
    private fun forwardDnsQuery(query: ByteArray, dnsServer: InetAddress): ByteArray? {
        return try {
            val socket = DatagramSocket()
            protect(socket) // 이 소켓은 VPN 터널을 거치지 않고 실제 네트워크로 직접 나감 (무한루프 방지 필수)
            socket.soTimeout = 5000

            socket.send(DatagramPacket(query, query.size, dnsServer, 53))

            val respBuf = ByteArray(4096)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)
            socket.close()

            respBuf.copyOfRange(0, respPacket.length)
        } catch (e: Exception) {
            null
        }
    }

    /** API 29+에서 소켓 소유 UID -> (패키지명, UID) 역추적 */
    private fun resolveOwnerPackage(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): Pair<String?, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Pair(null, -1)
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val uid = cm.getConnectionOwnerUid(
                17, // UDP
                InetSocketAddress(InetAddress.getByName(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByName(dstIp), dstPort)
            )
            if (uid <= 0) return Pair(null, -1)
            Pair(packageManager.getPackagesForUid(uid)?.firstOrNull(), uid)
        } catch (e: Exception) {
            Pair(null, -1)
        }
    }

    private fun logDnsQuery(appPackage: String?, appUid: Int, domain: String, dnsServerIp: String) {
        val cell = cellInfoLogger.getRegisteredCell()
        val now = System.currentTimeMillis()

        // 네트워크를 실제로 쓴 앱(UID)의 상태 + 그 순간 화면에 떠 있던 앱
        // 상태 판정은 "화면 앱 vs 통신 앱 비교"를 우선으로 한다 (importance는 폴백).
        // UsageStats를 한 번만 조회하도록 상태/화면앱을 함께 구한다.
        val (importanceLabel, foregroundApp) =
            AppStateResolver.resolveStateAndForeground(this, appUid, appPackage, now)

        val entry = DnsLogEntry(
            timestamp = now,
            appPackage = appPackage ?: "unknown",
            domain = domain,
            dnsServer = dnsServerIp,
            cellNetworkType = cell?.networkType,
            mcc = cell?.mcc,
            mnc = cell?.mnc,
            cellId = cell?.cellId,
            areaCode = cell?.areaCode,
            pci = cell?.pci,
            signalDbm = cell?.signalDbm,
            importanceLabel = importanceLabel,
            foregroundApp = foregroundApp,
            cellIsStale = cell?.isStale ?: false
        )

        persistEntry(entry)

        // 로그를 직접 안 보고 있어도 즉시 알림으로 경고 (단, 같은 조합은 쿨다운 시간 안에 중복 방지)
        val result = riskEvaluator.evaluate(appPackage ?: "unknown", domain)
        if (result.isSuspicious) {
            val alertKey = "${result.category}|${appPackage ?: "unknown"}|$domain"
            if (shouldAlert(alertKey)) {
                AlertNotifier.notifySuspicious(
                    this,
                    "⚠ 의심스러운 네트워크 활동 감지",
                    result.reason
                )
                val alertEntry = RiskAlertEntry(
                    timestamp = System.currentTimeMillis(),
                    appPackage = appPackage ?: "unknown",
                    target = domain,
                    category = result.category,
                    reason = result.reason
                )
                LogStore.insertRiskAlert(this, alertEntry)
                persistRiskAlertToFile(alertEntry)
            }
        }
    }

    /** SQLite(LogStore)와 다운로드 폴더의 날짜별 CSV 파일에 동시에 기록 */
    private fun persistEntry(entry: DnsLogEntry) {
        LogStore.insert(this, entry)

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(entry.timestamp))
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
        val csvLine = listOf(
            timeStr, entry.appPackage, entry.domain, entry.dnsServer,
            entry.cellNetworkType ?: "", entry.mcc ?: "", entry.mnc ?: "",
            entry.cellId?.toString() ?: "", entry.areaCode?.toString() ?: "",
            entry.pci?.toString() ?: "", entry.signalDbm?.toString() ?: "",
            entry.importanceLabel ?: "", entry.foregroundApp ?: "",
            if (entry.cellIsStale) "stale" else ""
        ).joinToString(",") { it.replace(",", " ") }

        FileLogWriter.appendLine(this, dateStr, csvLine)
    }

    /**
     * 위험 판정(DNS 위험탐지 + IP직통통신의심 공통)을 ip-logger 방식처럼
     * 하루 한 파일(risk_alert_yyyy-MM-dd.csv)에 그대로 누적한다.
     */
    private fun persistRiskAlertToFile(entry: RiskAlertEntry) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(entry.timestamp))
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
        val csvLine = listOf(
            timeStr, entry.appPackage, entry.category, entry.target, entry.reason
        ).joinToString(",") { it.replace(",", " ") }

        FileLogWriter.appendRiskAlertLine(this, dateStr, csvLine)
    }

    private fun startHeartbeatThread() {
        Thread {
            while (running) {
                persistEntry(
                    DnsLogEntry(
                        timestamp = System.currentTimeMillis(),
                        appPackage = "SYSTEM_HEARTBEAT",
                        domain = "(service alive)",
                        dnsServer = "-",
                        cellNetworkType = null, mcc = null, mnc = null,
                        cellId = null, areaCode = null, pci = null, signalDbm = null
                    )
                )
                try {
                    Thread.sleep(60_000) // 60초마다 생존 기록
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun startDirectIpMonitorThread() {
        Thread {
            var intervalStart = System.currentTimeMillis()
            while (running) {
                try {
                    Thread.sleep(120_000) // 2분마다 검사
                } catch (e: InterruptedException) {
                    break
                }
                val checkedAt = System.currentTimeMillis()
                directIpMonitor.checkForDirectIpActivity(intervalStart) { pkg, txDelta, rxDelta ->
                    val alertKey = "IP직통통신의심|$pkg"
                    if (shouldAlert(alertKey)) {
                        val reason = "$pkg 앱이 DNS 조회 기록 없이 데이터를 주고받았습니다 " +
                            "(송신 ${txDelta / 1024}KB / 수신 ${rxDelta / 1024}KB). " +
                            "도메인 없이 IP로 직접 통신하는 RAT의 전형적 패턴일 수 있습니다."
                        AlertNotifier.notifySuspicious(this, "⚠ IP 직통 통신 의심", reason)
                        val alertEntry = RiskAlertEntry(
                            timestamp = System.currentTimeMillis(),
                            appPackage = pkg,
                            target = "(도메인 없음)",
                            category = "IP직통통신의심",
                            reason = reason
                        )
                        LogStore.insertRiskAlert(this, alertEntry)
                        persistRiskAlertToFile(alertEntry)
                    }
                }
                intervalStart = checkedAt
            }
        }.start()
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        vpnInterface?.close()
        super.onDestroy()
    }

    override fun onRevoke() {
        running = false
        isRunning = false
        vpnInterface?.close()
        super.onRevoke()
    }
}

data class DnsLogEntry(
    val timestamp: Long,
    val appPackage: String,
    val domain: String,
    val dnsServer: String,
    val cellNetworkType: String?,
    val mcc: String?,
    val mnc: String?,
    val cellId: Long?,
    val areaCode: Int?,
    val pci: Int?,
    val signalDbm: Int?,
    val importanceLabel: String? = null, // 네트워크를 쓴 앱의 상태 (FOREGROUND/BACKGROUND_...)
    val foregroundApp: String? = null,   // 그 순간 화면에 떠 있던 앱 이름 (UsageStats 기준)
    val cellIsStale: Boolean = false     // true면 셀 정보가 폴백(직전 셀 정보)임
)
