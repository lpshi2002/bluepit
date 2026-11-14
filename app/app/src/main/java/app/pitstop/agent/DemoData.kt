package app.pitstop.agent

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 시연용 기본값. 정비소는 실제 업체가 아닌 샘플이고, 교체 주기는 취급설명서 확인 전 예시 값이다.
 * 실제 블루핸즈 목록은 공공데이터 "자동차정비업체 표준데이터"로 바꿀 예정이다.
 */
object DemoData {
    /** Vehicle SDK에 연결하지 못했을 때 쓰는 가상 주행거리. */
    const val FALLBACK_ODOMETER_KM = 32_000.0

    val shops = listOf(
        Shop("demo-gangnam", "샘플 블루핸즈 강남", "서울 강남구 (샘플)", 37.4950, 127.0300),
        Shop("demo-yeoksam", "샘플 블루핸즈 역삼", "서울 강남구 (샘플)", 37.5005, 127.0400, partner = true),
        Shop("demo-gaepo", "샘플 블루핸즈 개포", "서울 강남구 (샘플)", 37.4696, 127.0545, partner = true),
        Shop("demo-bundang", "샘플 블루핸즈 분당", "경기 성남시 분당구 (샘플)", 37.3800, 127.1150),
        Shop("demo-pangyo", "샘플 블루핸즈 판교", "경기 성남시 분당구 (샘플)", 37.3950, 127.1100),
    )

    /** 내비 즐겨찾기에 집·회사가 없을 때 쓰는 장소. */
    val fallbackPlaces = listOf(
        Place(PlaceKind.HOME, "집 (샘플)", 37.3830, 127.1190),
        Place(PlaceKind.OFFICE, "회사 (샘플)", 37.4985, 127.0330),
    )

    private val weekdays = DayOfWeek.entries.take(5).toSet()
    val availability = listOf(
        Availability(weekdays, 18, PlaceKind.OFFICE, 10, "A", "퇴근길"),
        Availability(weekdays, 8, PlaceKind.OFFICE, 30, "B", "출근길에 맡기기"),
        Availability(weekdays, 12, PlaceKind.OFFICE, 0, "C", "점심"),
        Availability(setOf(DayOfWeek.SATURDAY), 10, PlaceKind.HOME, 0, "S", "토요일"),
    )

    /** 차량 SDK로 동력계를 알아내지 못했을 때 쓰는 차종. */
    const val DEFAULT_ENGINE_SPEC = "nx4-t16"
    const val DEFAULT_EV_SPEC = "ne1"

    /**
     * 처음 실행할 때의 교체 기록. 시연 장면이 나오도록 맞췄다.
     * - 타이어 위치 교환이 약 1,000 km 남아 바로 첫 제안이 나온다.
     * - 엔진오일은 5개월 전에 갈아 통상 조건(12개월)으로는 여유가 있다. 짧은 주행이 감지돼
     *   가혹 조건(6개월)으로 바뀌면 약 한 달 안에 교체 시점이 와 바로 제안이 나온다.
     * - 냉각수는 아직 한 번도 교체하지 않아 최초 주기를 쓴다.
     */
    fun initialItems(spec: VehicleSpec, currentKm: Double, today: LocalDate) = spec.rules.map { rule ->
        val km = rule.normal.km
        when (rule.id) {
            "trans-oil", "reduction-gear-oil-replace" -> MaintenanceItem(rule.id, 0.0, today.minusYears(3))
            "tire-rotation" -> MaintenanceItem(rule.id, currentKm - 9_000, today.minusMonths(5))
            "engine-oil" -> MaintenanceItem(rule.id, currentKm - (rule.severe!!.km!! - 1_500), today.minusMonths(5))
            "coolant" -> MaintenanceItem(rule.id, currentKm - 45_000, today.minusYears(3), replacedBefore = false)
            "cabin-filter" -> MaintenanceItem(rule.id, currentKm - 9_000, today.minusMonths(7))
            else -> MaintenanceItem(rule.id, currentKm - (km ?: 0) * 0.4, today.minusMonths(10))
        }
    }

    /** 시연용 하루 주행. 하루 km를 trips번에 나눠 평균 kmh로 달린 것으로 기록한다. */
    fun trips(date: LocalDate, kmPerDay: Double, trips: Int, kmh: Double, startIndex: Int = 0): List<Trip> {
        val km = kmPerDay / trips
        return List(trips) { i ->
            val congested = kmh < 20 || (kmh == 35.0 && (startIndex + i) % 4 == 3)
            Trip(date, km, (km / kmh * 60).toInt().coerceAtLeast(1),
                if (congested) 1.8 else 0.8, if (congested) 0.6 else 0.2)
        }
    }
}

/** 예약 확정 방법. 지금은 시연용 구현만 있다. 현대차 예약 시스템과 제휴하면 실제 구현으로 바꾼다. */
fun interface BookingService {
    fun book(option: BookingOption): Booking
}

class DemoBookingService : BookingService {
    override fun book(option: BookingOption) = Booking(
        shopId = option.shop.id,
        date = option.date,
        hour = option.hour,
        minute = option.minute,
        confirmation = "PLAN-${java.util.UUID.randomUUID()}",
    )
}
