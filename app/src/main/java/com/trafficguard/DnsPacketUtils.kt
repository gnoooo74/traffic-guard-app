package com.trafficguard

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * IPv4 + UDP 패킷을 직접 파싱/생성하기 위한 저수준 유틸.
 * VpnService의 tun 인터페이스는 raw IP 패킷을 그대로 주고받기 때문에
 * 라이브러리 없이 헤더를 직접 다뤄야 한다.
 */
object DnsPacketUtils {

    data class ParsedUdpPacket(
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
        val ipHeaderLen: Int
    )

    /** raw IPv4 패킷에서 UDP 헤더까지 파싱. UDP가 아니거나 형식이 이상하면 null. */
    fun parseUdp(packet: ByteArray, length: Int): ParsedUdpPacket? {
        if (length < 20) return null
        val versionIhl = packet[0].toInt() and 0xFF
        val version = versionIhl shr 4
        if (version != 4) return null // IPv6는 이 버전에서 미지원

        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // 17 = UDP

        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(16, 20))

        val udpStart = ihl
        val srcPort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        val udpLen = ((packet[udpStart + 4].toInt() and 0xFF) shl 8) or (packet[udpStart + 5].toInt() and 0xFF)

        val payloadStart = udpStart + 8
        val payloadLen = (udpLen - 8).coerceAtMost(length - payloadStart)
        if (payloadLen <= 0 || payloadStart + payloadLen > length) return null

        return ParsedUdpPacket(
            srcIp, dstIp, srcPort, dstPort,
            packet.copyOfRange(payloadStart, payloadStart + payloadLen),
            ihl
        )
    }

    /** DNS 질의 패킷(payload)에서 조회하려는 도메인 이름을 추출 */
    fun extractQueryName(dnsPayload: ByteArray): String? {
        if (dnsPayload.size < 12) return null // DNS 헤더 12바이트
        var pos = 12
        val sb = StringBuilder()
        try {
            while (pos < dnsPayload.size) {
                val len = dnsPayload[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
                if (pos + len > dnsPayload.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dnsPayload, pos, len, Charsets.US_ASCII))
                pos += len
            }
        } catch (e: Exception) {
            return null
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * DNS 응답을 원래 요청자(앱)에게 돌려주기 위한 IPv4+UDP 패킷 생성.
     * 주소/포트를 뒤집고(src<->dst) 체크섬을 다시 계산한다.
     */
    fun buildResponsePacket(
        originalSrcIp: ByteArray, // 이제 응답의 dst가 됨 (원래 질의자)
        originalDstIp: ByteArray, // 이제 응답의 src가 됨 (원래 DNS 서버)
        srcPort: Int,             // 53
        dstPort: Int,             // 원래 질의자의 포트
        dnsResponsePayload: ByteArray
    ): ByteArray {
        val udpLen = 8 + dnsResponsePayload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)

        // ---- IPv4 헤더 ----
        buf.put((0x45).toByte())      // version(4) + IHL(5)
        buf.put(0)                    // TOS
        buf.putShort(totalLen.toShort())
        buf.putShort(0)               // identification
        buf.putShort(0x4000.toShort()) // flags: don't fragment
        buf.put(64)                   // TTL
        buf.put(17)                   // protocol: UDP
        buf.putShort(0)               // header checksum (나중에 계산)
        buf.put(originalDstIp)        // src ip = 원래 DNS 서버
        buf.put(originalSrcIp)        // dst ip = 원래 질의 앱

        val ipHeaderBytes = buf.array().copyOfRange(0, 20)
        val ipChecksum = checksum(ipHeaderBytes)
        buf.putShort(10, ipChecksum.toShort())

        // ---- UDP 헤더 ----
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // UDP checksum: 0 = 검증 생략 (VPN 터널 내부라 실무상 허용)

        // ---- payload ----
        buf.put(dnsResponsePayload)

        return buf.array()
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
