package com.trafficguard

/**
 * 타이포스쿼팅 탐지의 기준이 되는 "정상 도메인" 목록.
 * 카테고리별로 분리해서 나중에 유지보수하기 쉽게 관리한다.
 * 필요하면 여기에 계속 추가하면 됨 (많을수록 탐지 범위 넓어짐).
 */
object TrustedDomains {

    private val banks = listOf(
        "kbstar.com", "shinhan.com", "wooribank.com", "hanabank.com",
        "nonghyup.com", "ibk.co.kr", "citibank.co.kr", "sc.co.kr",
        "kdb.co.kr", "suhyup-bank.com", "knbank.co.kr", "jbbank.co.kr",
        "bnkfg.com", "dgb.co.kr", "kakaobank.com", "kbanknow.com",
        "tossbank.com"
    )

    private val cardsAndPay = listOf(
        "toss.im", "kakaopay.com", "naverpay.com", "payco.com",
        "samsungcard.com", "hyundaicard.com", "shinhancard.com",
        "kbcard.com", "lottecard.co.kr", "bccard.com", "hanacard.co.kr"
    )

    private val shopping = listOf(
        "coupang.com", "gmarket.co.kr", "11st.co.kr", "ssg.com",
        "auction.co.kr", "interpark.com", "wemakeprice.com",
        "tmon.co.kr", "musinsa.com", "oliveyoung.co.kr",
        "yes24.com", "aladin.co.kr", "kurly.com"
    )

    private val portalsAndPlatforms = listOf(
        "naver.com", "daum.net", "kakao.com", "google.com",
        "youtube.com", "instagram.com", "facebook.com",
        "netflix.com", "coupangplay.com", "watcha.com"
    )

    private val telecom = listOf(
        "kt.com", "skt.com", "uplus.co.kr", "lguplus.com"
    )

    private val deliveryAndLogistics = listOf(
        "cjlogistics.com", "hanjin.co.kr", "epost.go.kr",
        "baemin.com", "coupangeats.com", "yogiyo.co.kr"
    )

    private val government = listOf(
        "gov.kr", "korea.kr", "nts.go.kr", "hometax.go.kr",
        "gov-dooriban.go.kr", "police.go.kr", "epeople.go.kr",
        "minwon.go.kr", "nps.or.kr", "nhis.or.kr"
    )

    private val cryptoExchanges = listOf(
        "upbit.com", "bithumb.com", "coinone.co.kr", "korbit.co.kr"
    )

    /** 전체 목록 (중복 제거) */
    val all: List<String> by lazy {
        (banks + cardsAndPay + shopping + portalsAndPlatforms +
                telecom + deliveryAndLogistics + government + cryptoExchanges)
            .distinct()
    }
}
