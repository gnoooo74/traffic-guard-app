package com.trafficguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat

/**
 * 현재 등록된(camping) 셀타워 정보를 스냅샷으로 반환한다.
 * 네트워크 로그(iplogger)에 이 스냅샷을 함께 기록하면
 * "어느 기지국/셀에 붙어 있을 때 이 트래픽이 발생했는지"를 추적할 수 있다.
 */
data class CellSnapshot(
    val timestampMs: Long,
    val networkType: String,   // "LTE", "NR", "WCDMA", "GSM", "UNKNOWN"
    val mcc: String?,
    val mnc: String?,
    val cellId: Long?,         // CID / NCI
    val areaCode: Int?,        // LAC(2G/3G) 또는 TAC(4G/5G)
    val pci: Int?,             // Physical Cell ID (LTE/NR), 3G는 PSC로 대체
    val signalDbm: Int?,       // 대략적 신호 강도
    val isRegistered: Boolean
)

class CellInfoLogger(private val context: Context) {

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 현재 등록된 셀 정보 목록을 반환. 보통 리스트의 첫 번째(isRegistered=true)가
     * 실제 카메라(camping) 중인 셀이고, 나머지는 인접 셀(neighbor) 정보다.
     */
    fun getCurrentCellSnapshots(): List<CellSnapshot> {
        if (!hasLocationPermission()) {
            return emptyList()
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val now = System.currentTimeMillis()

        val cellInfoList: List<CellInfo> = try {
            tm.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

        return cellInfoList.mapNotNull { info -> parseCellInfo(info, now) }
    }

    /** 등록된(camping) 셀 하나만 필요할 때 */
    fun getRegisteredCell(): CellSnapshot? {
        return getCurrentCellSnapshots().firstOrNull { it.isRegistered }
    }

    private fun parseCellInfo(info: CellInfo, now: Long): CellSnapshot? {
        val registered = info.isRegistered
        val dbm = try {
            info.cellSignalStrength?.dbm
        } catch (e: Exception) {
            null
        }

        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                CellSnapshot(
                    timestampMs = now,
                    networkType = "LTE",
                    mcc = id.mccString,
                    mnc = id.mncString,
                    cellId = id.ci.takeIfValid(),
                    areaCode = id.tac.takeIfValidInt(),
                    pci = id.pci.takeIfValidInt(),
                    signalDbm = dbm,
                    isRegistered = registered
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                CellSnapshot(
                    timestampMs = now,
                    networkType = "WCDMA",
                    mcc = id.mccString,
                    mnc = id.mncString,
                    cellId = id.cid.takeIfValid(),
                    areaCode = id.lac.takeIfValidInt(),
                    pci = id.psc.takeIfValidInt(), // 3G는 PSC가 PCI 역할
                    signalDbm = dbm,
                    isRegistered = registered
                )
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                CellSnapshot(
                    timestampMs = now,
                    networkType = "GSM",
                    mcc = id.mccString,
                    mnc = id.mncString,
                    cellId = id.cid.takeIfValid(),
                    areaCode = id.lac.takeIfValidInt(),
                    pci = null, // GSM은 PCI 개념 없음
                    signalDbm = dbm,
                    isRegistered = registered
                )
            }
            else -> {
                // CellInfoNr은 API 29+에서만 존재, 리플렉션 없이 안전하게 처리
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                    val id = info.cellIdentity as? CellIdentityNr
                    CellSnapshot(
                        timestampMs = now,
                        networkType = "NR",
                        mcc = id?.mccString,
                        mnc = id?.mncString,
                        cellId = id?.nci?.takeIfValid(),
                        areaCode = id?.tac?.takeIfValidInt(),
                        pci = id?.pci?.takeIfValidInt(),
                        signalDbm = dbm,
                        isRegistered = registered
                    )
                } else null
            }
        }
    }

    private fun Long.takeIfValid(): Long? =
        if (this == Long.MAX_VALUE || this < 0) null else this

    private fun Int.takeIfValidInt(): Int? =
        if (this == Int.MAX_VALUE || this < 0) null else this
}
