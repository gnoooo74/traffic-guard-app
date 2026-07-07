package com.trafficguard

/**
 * DNS 조회 하나가 들어올 때마다 즉시 위험 여부를 판단하는 규칙 엔진.
 * 무거운 분석(전체 스캔)은 앱 시작 시 한 번만 캐시해두고,
 * 여기서는 캐시된 결과 + 문자열 비교만 수행해 실시간성을 유지한다.
 */
class RiskEvaluator(
    private val riskScoreCache: Map<String, Int> // packageName -> RiskScanner 점수
) {

    // 자주 표적이 되는 브랜드/은행/쇼핑몰 도메인 (타이포스쿼팅 비교 기준)
    // 목록 자체는 TrustedDomains.kt에서 카테고리별로 관리 (은행/카드/쇼핑/포털/통신/정부/거래소 등)
    private val trustedDomains = TrustedDomains.all

    data class RiskResult(val isSuspicious: Boolean, val reason: String)

    fun evaluate(appPackage: String, domain: String): RiskResult {
        // 1) 위험 앱 기준
        val appScore = riskScoreCache[appPackage] ?: 0
        if (appScore >= 50) {
            return RiskResult(
                true,
                "위험도 높은 앱($appPackage, 점수 $appScore)이 '$domain'에 접속을 시도했습니다."
            )
        }

        // 2) 타이포스쿼팅 기준
        val normalizedDomain = domain.lowercase().removeSuffix(".")

        // 먼저 "정상 도메인 자체이거나 그 하위 도메인(서브도메인)인지" 확인.
        // 예: m.naver.com, mail.google.com 등은 naver.com/google.com의 정상 서브도메인이므로
        // SSL 인증서 체인이 뚫리지 않은 이상 신뢰할 수 있음 -> 타이포스쿼팅 검사 자체를 건너뜀
        val isLegitSubdomain = trustedDomains.any { trusted ->
            normalizedDomain == trusted || normalizedDomain.endsWith(".$trusted")
        }
        if (isLegitSubdomain) {
            return RiskResult(false, "")
        }

        for (trusted in trustedDomains) {
            val distance = levenshtein(normalizedDomain, trusted)
            // 길이 차이가 너무 크면 비교 의미 없음 (완전히 다른 도메인)
            if (distance in 1..2 && kotlin.math.abs(normalizedDomain.length - trusted.length) <= 2) {
                return RiskResult(
                    true,
                    "'$domain'이(가) 정상 도메인 '$trusted'와(과) 철자가 유사합니다 (가짜 사이트 의심)."
                )
            }
        }

        return RiskResult(false, "")
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
