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
    val isRegistered: Boolean,
    val isStale: Boolean = false // true면 이번 조회 실패로 직전에 성공한 셀 정보를 폴백으로 쓴 것
)

class CellInfoLogger(private val context: Context) {

    // 가장 최근에 성공적으로 얻은 등록 셀. allCellInfo가 일시적으로 비거나 stale할 때 폴백으로 쓴다.
    @Volatile
    private var lastGoodCell: CellSnapshot? = null

    // requestCellInfoUpdate를 매 조회마다 남발하지 않도록 최소 간격(ms)을 둔다.
    @Volatile
    private var lastUpdateRequestMs: Long = 0
    private val updateRequestIntervalMs = 10_000L

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

        val snapshots = cellInfoList.mapNotNull { info -> parseCellInfo(info, now) }

        // allCellInfo가 비었으면(시스템 캐시가 갱신을 멈춘 상태) 능동적으로 갱신을 요청한다.
        // 요청 결과는 비동기로 시스템 캐시에 반영되므로, 다음 조회부터 정상화된다.
        if (snapshots.isEmpty()) {
            requestCellInfoUpdateThrottled(tm, now)
        }

        return snapshots
    }

    /**
     * 등록된(camping) 셀 하나. allCellInfo가 일시적으로 실패하면
     * 최근에 성공했던 셀(lastGoodCell)을 폴백으로 반환한다.
     */
    fun getRegisteredCell(): CellSnapshot? {
        val fresh = getCurrentCellSnapshots().firstOrNull { it.isRegistered }
        if (fresh != null) {
            lastGoodCell = fresh
            return fresh
        }
        // 이번 조회는 실패했지만, 직전에 성공한 셀 정보가 있으면 그걸로 폴백 (stale로 표시)
        return lastGoodCell?.copy(isStale = true)
    }

    /**
     * 시스템에 "셀 정보를 새로 측정해서 캐시를 갱신하라"고 능동 요청한다. (API 29+)
     * allCellInfo가 오래 돌면 시스템이 갱신을 멈추는 문제를 이걸로 되살린다.
     * 남발 방지를 위해 최소 간격(updateRequestIntervalMs)을 둔다.
     */
    private fun requestCellInfoUpdateThrottled(tm: TelephonyManager, now: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (now - lastUpdateRequestMs < updateRequestIntervalMs) return
        if (!hasLocationPermission()) return

        lastUpdateRequestMs = now
        try {
            tm.requestCellInfoUpdate(
                context.mainExecutor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        // 갱신된 결과 중 등록 셀이 있으면 즉시 캐시에 반영해 둔다.
                        val parsed = cellInfo.mapNotNull { parseCellInfo(it, System.currentTimeMillis()) }
                        parsed.firstOrNull { it.isRegistered }?.let { lastGoodCell = it }
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        // 갱신 실패는 조용히 무시 (다음 주기에 재시도)
                    }
                }
            )
        } catch (e: Exception) {
            // requestCellInfoUpdate 자체가 실패해도 앱 동작에는 영향 없도록 무시
        }
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
                    cellId = id.ci.toValidLong(),
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
                    cellId = id.cid.toValidLong(),
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
                    cellId = id.cid.toValidLong(),
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

    /** Int로 제공되는 CID/CI(GSM/WCDMA/LTE)를 Long CellSnapshot 필드에 넣기 위한 안전 변환 */
    private fun Int.toValidLong(): Long? =
        if (this == Int.MAX_VALUE || this < 0) null else this.toLong()
}
