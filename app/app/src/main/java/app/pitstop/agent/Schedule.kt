package app.pitstop.agent

/**
 * 차종·엔진별 정기 점검 주기. 값은 현대차 공식 취급설명서(ownersmanual.hyundai.com, 국문판)의
 * "통상 조건"·"가혹 조건" 표에서 가져왔다. 출처와 조사 방법은 docs/maintenance-schedules.md에 있다.
 */
enum class Powertrain(val label: String) {
    GASOLINE("가솔린"),
    GASOLINE_TURBO("가솔린 터보"),
    LPG("LPG"),
    DIESEL("디젤"),
    HYBRID("하이브리드"),
    EV("전기"),
}

/**
 * 가혹 조건. number는 국문 취급설명서 가혹 조건 목록의 번호다.
 * OLD_VEHICLE은 국문판에 없고 미국판 취급설명서의 "10년 또는 150,000km 이상 운행" 조건이다.
 */
enum class SevereCondition(val number: Int?, val label: String) {
    SHORT_TRIPS(1, "짧은 거리 반복 주행"),
    DUST(2, "모래·먼지가 많은 지역 주행"),
    IDLING(3, "과다한 공회전"),
    CONGESTION(4, "교통 체증이 심한 구역 주행"),
    ROUGH_ROAD(5, "잦은 험로 주행"),
    MOUNTAIN(6, "산길·오르막·내리막 주행"),
    COMMERCIAL(7, "택시·상용차 등으로 사용"),
    HIGH_SPEED(8, "잦은 고속 주행·급가감속"),
    STOP_AND_GO(9, "정지와 출발의 잦은 반복"),
    SALT_COLD(10, "소금·부식 물질 위 또는 한랭 지역 주행"),
    TOWING(11, "견인·캠핑용 사용, 지붕 짐칸 탑재"),
    NON_RECOMMENDED_OIL(12, "추천하지 않는 엔진 오일 사용"),
    OLD_VEHICLE(null, "10년 또는 150,000 km 이상 운행"),
}

enum class ServiceAction(val verb: String) { REPLACE("교체"), INSPECT("점검"), ROTATE("교환") }

/** 주행거리와 기간 중 먼저 오는 쪽. 둘 중 하나만 있는 항목도 있다. */
data class Interval(val km: Int?, val months: Long?) {
    fun describe(): String = if (km == null && months == null) "정해진 주기 없음" else listOfNotNull(km?.let { "%,d km".format(it) }, months?.let { "${it}개월" })
        .joinToString(" 또는 ", prefix = "매 ")
}

data class ItemRule(
    val id: String,
    val name: String,
    val action: ServiceAction,
    val normal: Interval,
    /** 한 번도 교체하지 않았을 때의 주기. 냉각수처럼 최초 주기가 따로 있는 항목에만 있다. */
    val first: Interval? = null,
    val severe: Interval? = null,
    /** 이 조건 중 하나라도 해당하면 severe 주기를 쓴다. */
    val severeWhen: Set<SevereCondition> = emptySet(),
)

data class VehicleSpec(
    val id: String,
    val model: String,
    val engine: String,
    val powertrain: Powertrain,
    /** 근거로 쓴 취급설명서. 예: "투싼(NX4) 2026" */
    val manual: String,
    val rules: List<ItemRule>,
) {
    val label get() = "$model $engine"

    fun rule(id: String) = rules.firstOrNull { it.id == id }
}

object ScheduleCatalog {
    private val ALL_CONDITIONS = SevereCondition.entries.toSet()

    private fun engineOil(normalKm: Int, severeKm: Int) = ItemRule(
        "engine-oil", "엔진오일·오일필터", ServiceAction.REPLACE,
        normal = Interval(normalKm, 12), severe = Interval(severeKm, 6), severeWhen = ALL_CONDITIONS,
    )

    private fun airCleaner(km: Int) = ItemRule("air-cleaner", "에어클리너 필터", ServiceAction.REPLACE, Interval(km, null))

    private fun sparkPlug(km: Int) = ItemRule("spark-plug", "점화 플러그", ServiceAction.REPLACE, Interval(km, null))

    private val cabinFilter = ItemRule("cabin-filter", "에어컨 필터", ServiceAction.REPLACE, Interval(null, 12))
    private val cabinFilterCheck = ItemRule("cabin-filter", "에어컨 필터", ServiceAction.INSPECT, Interval(null, 12))
    private val cabinFilterByKm = ItemRule("cabin-filter", "에어컨 필터", ServiceAction.REPLACE, Interval(15_000, null))
    private val brakeFluid = ItemRule("brake-fluid", "브레이크액", ServiceAction.REPLACE, Interval(50_000, null))
    private val coolant = ItemRule(
        "coolant", "냉각수", ServiceAction.REPLACE,
        normal = Interval(40_000, 24), first = Interval(200_000, 120),
    )
    private val tireRotation = ItemRule("tire-rotation", "타이어 위치", ServiceAction.ROTATE, Interval(10_000, null))
    private val dieselFuelFilter = ItemRule("fuel-filter", "연료필터 카트리지", ServiceAction.REPLACE, Interval(60_000, null))
    private val reductionGearOil = ItemRule("reduction-gear-oil", "감속기 오일", ServiceAction.INSPECT, Interval(60_000, null))

    /** 엔진차 공통 항목. 엔진별로 다른 항목만 인자로 받는다. */
    private fun engineRules(oil: ItemRule, airCleanerKm: Int, plugKm: Int?, cabin: ItemRule, vararg extra: ItemRule) =
        listOfNotNull(oil, tireRotation, airCleaner(airCleanerKm), plugKm?.let(::sparkPlug), cabin, brakeFluid, coolant) + extra

    private val evRules = listOf(tireRotation, reductionGearOil, cabinFilter, brakeFluid, coolant)

    private fun conditionalOil(id: String, name: String, km: Int, numbers: Set<Int>) =
        ItemRule(id, name, ServiceAction.REPLACE, Interval(null, null), severe = Interval(km, null),
            severeWhen = SevereCondition.entries.filter { it.number in numbers }.toSet())

    fun fluidWarning(ev: Boolean) = ItemRule(if (ev) "brake-fluid-level" else "oil-level",
        if (ev) "브레이크액 양" else "엔진오일 양", ServiceAction.INSPECT, Interval(null, null))

    val specs = listOf(
        VehicleSpec("dn8-g20", "쏘나타", "2.0 가솔린", Powertrain.GASOLINE, "쏘나타(DN8) 2026",
            engineRules(engineOil(15_000, 7_500), 60_000, 165_000, cabinFilter)),
        VehicleSpec("dn8-l20", "쏘나타", "2.0 LPG", Powertrain.LPG, "쏘나타(DN8) 2026",
            engineRules(engineOil(15_000, 7_500), 60_000, 165_000, cabinFilter)),
        VehicleSpec("dn8-turbo", "쏘나타", "1.6·2.5 터보", Powertrain.GASOLINE_TURBO, "쏘나타(DN8) 2026",
            engineRules(engineOil(10_000, 5_000), 60_000, 70_000, cabinFilter)),
        VehicleSpec("dn8-hev", "쏘나타", "하이브리드", Powertrain.HYBRID, "쏘나타 하이브리드(DN8 HEV) 2026",
            engineRules(engineOil(15_000, 7_500), 40_000, 165_000, cabinFilter)),
        VehicleSpec("gn7-ice", "그랜저", "가솔린·LPG", Powertrain.GASOLINE, "그랜저(GN7) 2026",
            engineRules(engineOil(15_000, 7_500), 40_000, 160_000, cabinFilter)),
        VehicleSpec("gn7-hev", "그랜저", "1.6 터보 하이브리드", Powertrain.HYBRID, "그랜저 하이브리드(GN7 HEV) 2026",
            engineRules(engineOil(10_000, 5_000), 40_000, 160_000, cabinFilter)),
        VehicleSpec("nx4-t16", "투싼", "1.6 터보", Powertrain.GASOLINE_TURBO, "투싼(NX4) 2026",
            engineRules(engineOil(10_000, 5_000), 40_000, 75_000, cabinFilterCheck)),
        VehicleSpec("nx4-hev", "투싼", "1.6 터보 하이브리드", Powertrain.HYBRID, "투싼 하이브리드(NX4 HEV) 2026",
            engineRules(engineOil(10_000, 5_000), 40_000, 75_000, cabinFilter)),
        VehicleSpec("mx5-t25", "싼타페", "2.5 터보", Powertrain.GASOLINE_TURBO, "싼타페(MX5) 2026",
            engineRules(engineOil(10_000, 5_000), 40_000, 70_000, cabinFilter)),
        VehicleSpec("mx5-hev", "싼타페", "1.6 터보 하이브리드", Powertrain.HYBRID, "싼타페 하이브리드(MX5 HEV) 2026",
            engineRules(engineOil(10_000, 5_000), 40_000, 75_000, cabinFilter)),
        VehicleSpec("sx2-t16", "코나", "1.6 터보", Powertrain.GASOLINE_TURBO, "코나(SX2) 2025",
            engineRules(engineOil(10_000, 5_000), 60_000, 75_000, cabinFilter)),
        VehicleSpec("sx2-g20", "코나", "2.0 가솔린", Powertrain.GASOLINE, "코나(SX2) 2025",
            engineRules(engineOil(15_000, 7_500), 60_000, 165_000, cabinFilter)),
        VehicleSpec("sx2-hev", "코나", "하이브리드", Powertrain.HYBRID, "코나 하이브리드(SX2 HEV) 2025",
            engineRules(engineOil(15_000, 7_500), 60_000, 75_000, cabinFilter)),
        VehicleSpec("lx2-g38", "팰리세이드", "3.8 가솔린 (2024)", Powertrain.GASOLINE, "팰리세이드(LX2) 2024",
            engineRules(engineOil(15_000, 7_500), 40_000, 160_000, cabinFilterByKm)),
        VehicleSpec("lx2-d22", "팰리세이드", "2.2 디젤 (2024)", Powertrain.DIESEL, "팰리세이드(LX2) 2024",
            engineRules(engineOil(20_000, 10_000), 40_000, null, cabinFilterByKm, dieselFuelFilter)),
        VehicleSpec("lx3-t25", "팰리세이드", "2.5 터보", Powertrain.GASOLINE_TURBO, "팰리세이드(LX3) 2026",
            engineRules(engineOil(10_000, 5_000), 40_000, 160_000, cabinFilterCheck)),
        VehicleSpec("us4-l35", "스타리아", "3.5 LPG", Powertrain.LPG, "스타리아(US4) 2026",
            engineRules(engineOil(15_000, 7_500), 40_000, 160_000, cabinFilterCheck)),
        VehicleSpec("ne1", "아이오닉 5", "전기", Powertrain.EV, "아이오닉 5(NE1) 2026", evRules),
        VehicleSpec("ce1", "아이오닉 6", "전기", Powertrain.EV, "아이오닉 6(CE1) 2025", evRules),
    ).map { spec ->
        val rule = if (spec.powertrain == Powertrain.EV) {
            conditionalOil("reduction-gear-oil-replace", "감속기 오일", 120_000, setOf(2, 4, 6, 10))
        } else {
            val numbers = when (spec.id) {
                "nx4-t16", "nx4-hev" -> setOf(1) + (4..8)
                "dn8-turbo", "mx5-t25" -> (1..9).toSet() + 11
                "sx2-hev" -> (4..8).toSet() + setOf(10, 11)
                else -> setOf(1, 11) + (4..9)
            }
            conditionalOil("trans-oil", "변속기 오일", if (spec.id == "sx2-hev") 120_000 else 100_000, numbers)
        }
        spec.copy(rules = spec.rules + rule)
    }

    fun spec(id: String?) = specs.firstOrNull { it.id == id }
}
