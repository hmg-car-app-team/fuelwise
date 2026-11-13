package com.hmgcarapp.fuelwise.domain

enum class EnergyKind(val label: String) { FUEL("주유"), EV("충전") }

enum class FuelGrade(val label: String) { GASOLINE("휘발유"), PREMIUM("고급휘발유"), DIESEL("경유"), LPG("LPG") }

enum class EnergyMode(val label: String) { AUTO("자동"), FUEL("내연기관"), EV("전기차") }

enum class SortKey(val label: String) {
    COST("실질 비용순"), PRICE("가격순"), DISTANCE("우회 적은 순");
    /** 가정 단가 목록(충전소 스냅샷)에서는 '실질 비용'이 요금 근거가 없으므로 '우회·대기 기준 순'으로 표기한다. 주유소 목록은 불변. */
    fun labelFor(assumedList: Boolean): String = if (this == COST && assumedList) "우회·대기 기준 순" else label
}

data class LatLon(val lat: Double, val lon: Double)

/** 주유소/충전소 후보. 주유 가격은 가공 샘플(오피넷 원본 아님), 충전소는 공공데이터 스냅샷일 수 있음(단가는 가정). */
data class Candidate(
    val id: String,
    val name: String,
    val brand: String,
    val kind: EnergyKind,
    val pos: LatLon,
    /** 주유소: 등급별 리터당 가격(원) */
    val fuelPrices: Map<FuelGrade, Int> = emptyMap(),
    /** 충전소: kWh당 가격(원), 충전기 최대 출력(kW), 예상 대기(분) */
    val pricePerKwh: Int? = null,
    val chargerKw: Double? = null,
    val waitMin: Int = 0,
    /** 충전소: 단가가 원본 가격이 아니라 가정 단가인지(공공데이터 스냅샷은 항상 true). 대기 0분도 가정값. */
    val priceAssumed: Boolean = false,
)

/** 할인 카드/멤버십. brands 가 비어 있으면 모든 브랜드. */
data class Discount(
    val id: String,
    val name: String,
    val brands: Set<String> = emptySet(),
    val wonPerLiter: Int = 0,
    val evPercent: Int = 0,
) {
    fun appliesTo(c: Candidate) = brands.isEmpty() || c.brand in brands
}

data class Destination(val name: String, val pos: LatLon, val address: String = "")

data class Settings(
    val energyMode: EnergyMode = EnergyMode.AUTO,
    val fuelGrade: FuelGrade = FuelGrade.GASOLINE,
    val tankL: Int = 55,
    val kmPerL: Double = 12.0,
    val batteryKwh: Double = 77.4,
    val kmPerKwh: Double = 5.0,
    val evMaxKw: Int = 150,
    val chargeTargetPct: Int = 80,
    /** 신호가 없을 때 가정하는 현재 잔량(%) */
    val fallbackLevelPct: Int = 30,
    val lowFuelPct: Int = 20,
    val lowSocPct: Int = 20,
    val lowRangeKm: Int = 60,
    val evTimeValueWonPerMin: Int = 20,
    /** 샘플 목적지 인덱스(debuggable 시연 전용). null = 목적지 없음(기본) -> 후보를 직접 안내 */
    val destinationIndex: Int? = null,
    val sort: SortKey = SortKey.COST,
    /** '음성 응답 사용': 끄면 알림은 화면 배너+TTS 로만 하고 STT 청취를 하지 않는다(배너 버튼으로만 수락). */
    val voiceReply: Boolean = true,
)

object Presets {
    /** 가공 샘플 목적지(좌표는 공개 지도 기준 근사). 시연(debuggable)에서만 선택 가능 */
    val destinations = listOf(
        Destination("판교역(샘플 목적지)", LatLon(37.3948, 127.1112), "경기 성남시 분당구"),
        Destination("양재 시민의숲(샘플 목적지)", LatLon(37.4700, 127.0380), "서울 서초구"),
    )

    /** 가공 샘플 카드. 실제 카드사 상품이 아니다. */
    val discounts = listOf(
        Discount("card_a", "주유카드 A (A정유 -60원/L)", setOf("A정유"), wonPerLiter = 60),
        Discount("card_all", "멤버십 B (전 브랜드 -20원/L)", emptySet(), wonPerLiter = 20),
        Discount("ev_member", "충전 멤버십 (충전 10% 할인)", emptySet(), evPercent = 10),
    )
}

/** 내비 활성 경로의 실제 목적지(읽기 전용 질의 결과). [remainDistance] 는 SDK 가 준 남은 거리 값(단위 미확인: 계산에 쓰지 않는다). */
data class RouteDestination(val pos: LatLon, val name: String? = null, val remainDistance: Int? = null)

