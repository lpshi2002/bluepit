package app.pitstop.agent

import java.time.DayOfWeek
import java.time.LocalDate

/** 소모품 하나의 교체 기록. 주기와 이름은 차종 규칙(ItemRule)에서 ruleId로 찾는다. */
data class MaintenanceItem(
    val ruleId: String,
    val lastServiceKm: Double,
    val lastServiceDate: LocalDate,
    /** false면 아직 한 번도 교체하지 않아 최초 주기(ItemRule.first)를 쓴다. */
    val replacedBefore: Boolean = true,
    val booking: Booking? = null,
    val snoozedUntil: LocalDate? = null,
    val estimated: Boolean = false,
    val baselineCheck: Boolean = false,
    val cancelledShop: String? = null,
) {
    fun booked(booking: Booking) = copy(booking = booking, snoozedUntil = null, cancelledShop = null)

    fun snoozed(until: LocalDate) = copy(snoozedUntil = until)

    fun serviced(km: Double, date: LocalDate) =
        copy(lastServiceKm = km, lastServiceDate = date, replacedBefore = true, estimated = false, baselineCheck = false, cancelledShop = null, booking = null, snoozedUntil = null)
}

data class Booking(
    val shopId: String,
    val date: LocalDate,
    val hour: Int,
    val confirmation: String,
    val minute: Int = 0,
)

/** 그날 마지막으로 읽은 누적 주행거리(km). */
data class OdometerSample(val date: LocalDate, val km: Double)

enum class PlaceKind { HOME, OFFICE }

data class Place(val kind: PlaceKind, val name: String, val lat: Double, val lon: Double)

data class Shop(val id: String, val name: String, val address: String, val lat: Double, val lon: Double, val partner: Boolean = false,
    val opening: Int = 0, val closing: Int = 24 * 60)

/** 사용자가 정비소에 갈 수 있는 시간대. 예: 토요일 10시, 집 근처. */
data class Availability(val days: Set<DayOfWeek>, val hour: Int, val near: PlaceKind, val minute: Int = 0, val code: String = "", val label: String = "")

/** 에이전트가 판단에 쓰는 현재 상황 전체. */
data class Situation(
    val today: LocalDate,
    val currentKm: Double,
    val samples: List<OdometerSample>,
    val spec: VehicleSpec,
    val items: List<MaintenanceItem>,
    /** 주행 기록으로 감지했거나 사용자가 알려 준 가혹 조건. */
    val conditions: Set<SevereCondition>,
    val places: List<Place>,
    val shops: List<Shop>,
    val availability: List<Availability>,
    val scores: Map<SevereCondition, Double> = emptyMap(),
    val partnerDemo: Boolean = false,
    val lowFluid: Boolean = false,
)

/** 지금 적용하는 주기와 그 이유. */
data class AppliedInterval(val interval: Interval, val basis: Basis, val because: Set<SevereCondition>, val hci: Double? = null) {
    enum class Basis(val label: String) { WARNING("양 부족 신호"), NONE("정기 교체 없음"), ADJUSTED("조정됨"), FIRST("최초 주기"), NORMAL("통상 조건"), SEVERE("가혹 조건") }
}

data class BookingOption(
    val date: LocalDate,
    val hour: Int,
    val shop: Shop,
    val place: Place,
    val distanceKm: Double,
    val minute: Int = 0,
    val label: String = "",
    val detourMinutes: Double? = null,
)

/** 에이전트가 지금 사용자에게 할 한 가지 일. */
sealed interface AgentAction {
    data object AllGood : AgentAction

    /** 예약일이 오늘이거나 지났다. 같은 예약으로 묶인 소모품을 함께 안내한다. */
    data class VisitDay(val rules: List<ItemRule>, val booking: Booking, val shop: Shop?) : AgentAction

    /**
     * 예약을 제안한다. reschedule이면 기존 예약보다 교체 시점이 빨라져 앞당기자는 제안이다.
     * together는 교체 시점이 가까워 같은 날 함께 맡기면 좋은 소모품이다.
     */
    data class Propose(
        val rule: ItemRule,
        val applied: AppliedInterval,
        val dueDate: LocalDate,
        val options: List<BookingOption>,
        val reschedule: Boolean,
        val together: List<ItemRule>,
    ) : AgentAction

    /** 교체 시점이 앱으로 예약할 수 있는 가장 빠른 날보다 앞이다. */
    data class TooLate(val rule: ItemRule, val dueDate: LocalDate) : AgentAction
}
