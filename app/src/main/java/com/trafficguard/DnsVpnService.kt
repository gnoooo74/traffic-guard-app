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

    private lateinit var cellInfoLogger: CellInfoLogger

    companion object {
        const val CHANNEL_ID = "dns_monitor_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.trafficlogger.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        cellInfoLogger = CellInfoLogger(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startVpn()
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
            val appPackage = resolveOwnerPackage(udp.srcIp.hostAddress, udp.srcPort, udp.dstIp.hostAddress, 53)

            // 실제 DNS 서버로 질의를 그대로 전달 (protect()로 VPN 루프 방지)
            val responsePayload = forwardDnsQuery(udp.payload, udp.dstIp)

            // 로그 기록 (셀 정보 결합)
            logDnsQuery(appPackage, queryName, udp.dstIp.hostAddress)

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

    /** API 29+에서 소켓 소유 UID -> 패키지명 역추적 */
    private fun resolveOwnerPackage(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val uid = cm.getConnectionOwnerUid(
                17, // UDP
                InetSocketAddress(InetAddress.getByName(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByName(dstIp), dstPort)
            )
            if (uid <= 0) return null
            packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun logDnsQuery(appPackage: String?, domain: String, dnsServerIp: String) {
        val cell = cellInfoLogger.getRegisteredCell()

        val entry = DnsLogEntry(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage ?: "unknown",
            domain = domain,
            dnsServer = dnsServerIp,
            cellNetworkType = cell?.networkType,
            mcc = cell?.mcc,
            mnc = cell?.mnc,
            cellId = cell?.cellId,
            areaCode = cell?.areaCode,
            pci = cell?.pci,
            signalDbm = cell?.signalDbm
        )

        LogStore.insert(this, entry)
    }

    private fun startHeartbeatThread() {
        Thread {
            while (running) {
                LogStore.insert(
                    this,
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

    override fun onDestroy() {
        running = false
        vpnInterface?.close()
        super.onDestroy()
    }

    override fun onRevoke() {
        running = false
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
    val signalDbm: Int?
)
