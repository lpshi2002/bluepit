package app.pitstop.agent

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 교체 시점 예측과 예약 제안을 정하는 규칙. LLM 없이 같은 입력에 항상 같은 답을 낸다.
 * 블루핸즈 예약 규칙(3일 후~6주 이내, 월요일은 D-5까지)은 현대차 공식 예약 페이지 기준이다.
 */
object Planner {
    const val DEFAULT_DAILY_KM = 40.0
    private const val AVERAGE_WINDOW_DAYS = 28L
    private const val MIN_HISTORY_DAYS = 7L
    private const val BOOKING_MIN_DAYS = 3L
    private const val BOOKING_MONDAY_MIN_DAYS = 5L
    const val BOOKING_MAX_DAYS = 42L
    private const val TARGET_LEAD_DAYS = 7L
    private const val MAX_OPTIONS = 3
    /** 주 소모품 예정일로부터 이 기간 안에 교체할 소모품은 같은 날 함께 맡기자고 한다. */
    private const val TOGETHER_DAYS = 30L

    /** 최근 28일 하루 평균 주행거리. 기록이 7일보다 짧으면 null이고, 호출하는 쪽이 기본값을 쓴다. */
    fun dailyKm(samples: List<OdometerSample>, today: LocalDate): Double? {
        val recent = samples
            .filter { !it.date.isBefore(today.minusDays(AVERAGE_WINDOW_DAYS)) }
            .sortedBy { it.date }
        val first = recent.firstOrNull() ?: return null
        val last = recent.last()
        val days = ChronoUnit.DAYS.between(first.date, last.date)
        if (days < MIN_HISTORY_DAYS) return null
        return ((last.km - first.km) / days).coerceAtLeast(0.0)
    }

    /** 지금 적용할 주기. 최초 교체 전이면 최초 주기, 가혹 조건에 해당하면 가혹 조건 주기다. */
    fun applied(rule: ItemRule, item: MaintenanceItem, conditions: Set<SevereCondition>, scores: Map<SevereCondition, Double> = emptyMap()): AppliedInterval {
        if (!item.replacedBefore && rule.first != null) {
            return AppliedInterval(rule.first, AppliedInterval.Basis.FIRST, emptySet())
        }
        val because = rule.severeWhen intersect conditions
        if (rule.severe != null && because.isNotEmpty()) {
            return AppliedInterval(rule.severe, AppliedInterval.Basis.SEVERE, because)
        }
        if (rule.normal == Interval(null, null)) return AppliedInterval(rule.normal, AppliedInterval.Basis.NONE, emptySet())
        if (rule.id == "engine-oil") {
            val hci = 0.35 * (scores[SevereCondition.SHORT_TRIPS] ?: 0.0) +
                0.30 * (scores[SevereCondition.CONGESTION] ?: 0.0) +
                0.20 * (scores[SevereCondition.IDLING] ?: 0.0) +
                0.15 * (scores[SevereCondition.STOP_AND_GO] ?: 0.0)
            val normal = rule.normal.km!!
            val km = ((normal - (normal - rule.severe!!.km!!) * hci.pow(1.15)) / 100).roundToInt() * 100
            if (km < normal) return AppliedInterval(rule.normal.copy(km = km), AppliedInterval.Basis.ADJUSTED, emptySet(), hci)
        }
        return AppliedInterval(rule.normal, AppliedInterval.Basis.NORMAL, emptySet())
    }

    /** 남은 주행거리. 기간으로만 관리하는 항목은 null이다. */
    fun remainingKm(item: MaintenanceItem, interval: Interval, currentKm: Double): Double? =
        interval.km?.let { it - (currentKm - item.lastServiceKm) }

    /**
     * 주행거리 기준과 기간 기준 중 먼저 오는 교체 예정일. 이미 지났으면 오늘이다.
     * 주행거리로만 관리하는데 최근 주행이 없으면 예측할 수 없어 null이다.
     */
    fun dueDate(item: MaintenanceItem, interval: Interval, currentKm: Double, today: LocalDate, dailyKm: Double): LocalDate? {
        val byTime = interval.months?.let { item.lastServiceDate.plusMonths(it) }
        val remaining = remainingKm(item, interval, currentKm)
        val byDistance = when {
            remaining == null -> null
            remaining <= 0 -> today
            dailyKm <= 0 -> null
            else -> today.plusDays(ceil(remaining / dailyKm).toLong())
        }
        return listOfNotNull(byTime, byDistance).minOrNull()?.let { maxOf(it, today) }
    }

    fun isBookable(date: LocalDate, today: LocalDate): Boolean {
        val days = ChronoUnit.DAYS.between(today, date)
        val minDays = if (date.dayOfWeek == DayOfWeek.MONDAY) BOOKING_MONDAY_MIN_DAYS else BOOKING_MIN_DAYS
        return days in minDays..BOOKING_MAX_DAYS
    }

    /**
     * 교체 예정일 전에 갈 수 있는 예약 후보. 예정일 1주 전에 가까운 날, 그다음 정비소가 가까운 순이다.
     */
    fun options(dueDate: LocalDate, situation: Situation, immediate: Boolean = false): List<BookingOption> {
        val today = situation.today
        val target = if (immediate) today else maxOf(dueDate.minusDays(TARGET_LEAD_DAYS), today)
        val last = if (immediate) today.plusDays(7) else minOf(dueDate, today.plusDays(BOOKING_MAX_DAYS))
        val home = situation.places.firstOrNull { it.kind == PlaceKind.HOME }
        val office = situation.places.firstOrNull { it.kind == PlaceKind.OFFICE }
        val options = mutableListOf<BookingOption>()
        for (date in generateSequence(today) { it.plusDays(1) }.takeWhile { it <= last }) {
            for (slot in situation.availability.filter { date.dayOfWeek in it.days }) {
                val place = (if (slot.near == PlaceKind.HOME) home else office) ?: continue
                for (shop in situation.shops) {
                    if (slot.hour * 60 + slot.minute !in shop.opening until shop.closing) continue
                    if (shop.partner) {
                        if (!situation.partnerDemo || (date == today && slot.code != "A")) continue
                        val key = "${shop.id}|${date.toEpochDay()}|${slot.code}"
                        if (date != today && key.fold(7) { h, c -> 31 * h + c.code } % 4 == 0) continue
                    } else if (!isBookable(date, today) || slot.hour * 60 + slot.minute > 18 * 60) continue
                    val distance = distanceKm(place, shop)
                    val detour = if (slot.code == "A" && office != null && home != null) {
                        val extra = distanceKm(office, shop) + distanceKm(home, shop) -
                            distanceKm(office, Shop("", "", "", home.lat, home.lon))
                        extra.coerceAtLeast(0.0) * 1.3 / 30 * 60
                    } else null
                    val fits = when (slot.code) {
                        "A" -> shop.partner && detour != null && detour <= 5
                        "B", "C" -> distance <= 1.5
                        "S" -> distance <= 5
                        else -> true
                    }
                    if (fits) options += BookingOption(date, slot.hour, shop, place, distance, slot.minute, slot.label, detour)
                }
            }
        }
        return options.sortedWith(compareBy<BookingOption> { abs(ChronoUnit.DAYS.between(it.date, target)) }
            .thenBy { it.date }.thenBy { it.detourMinutes ?: it.distanceKm }).take(MAX_OPTIONS)
    }

    val frequentIds = setOf("engine-oil", "tire-rotation", "cabin-filter")

    fun history(rule: ItemRule, months: Long?, km: Double, today: LocalDate, daily: Double, year: Int?): MaintenanceItem {
        if (months == null) {
            val firstDate = year?.let { LocalDate.of(minOf(it, today.year), 1, 1) } ?: today
            return MaintenanceItem(rule.id, 0.0, firstDate, replacedBefore = false, estimated = true,
                baselineCheck = rule.id in frequentIds)
        }
        val date = today.minusMonths(months)
        return MaintenanceItem(rule.id, (km - daily * ChronoUnit.DAYS.between(date, today)).coerceAtLeast(0.0),
            date, estimated = true)
    }

    /** 소모품 하나의 현재 판단 결과. */
    data class Status(val item: MaintenanceItem, val rule: ItemRule, val applied: AppliedInterval, val dueDate: LocalDate?)

    fun statuses(situation: Situation): List<Status> {
        val daily = dailyKm(situation.samples, situation.today) ?: DEFAULT_DAILY_KM
        val warning = ScheduleCatalog.fluidWarning(situation.spec.powertrain == Powertrain.EV)
        val items = if (situation.lowFluid && situation.items.none { it.ruleId == warning.id })
            situation.items + MaintenanceItem(warning.id, situation.currentKm, situation.today) else situation.items
        return items.mapNotNull { item ->
            if (item.ruleId == warning.id && (situation.lowFluid || item.booking != null)) {
                return@mapNotNull Status(item, warning, AppliedInterval(warning.normal, AppliedInterval.Basis.WARNING, emptySet()), situation.today)
            }
            val rule = situation.spec.rule(item.ruleId) ?: return@mapNotNull null
            val applied = applied(rule, item, situation.conditions, situation.scores)
            Status(item, rule, applied, if (item.baselineCheck) situation.today else dueDate(item, applied.interval, situation.currentKm, situation.today, daily))
        }
    }

    /** 지금 할 일 하나를 고른다. 예약일 안내 → 예약 앞당기기 → 새 예약 제안 순이다. */
    fun decide(situation: Situation): AgentAction {
        val today = situation.today
        val all = statuses(situation)
        fun awake(status: Status) = status.item.snoozedUntil?.isAfter(today) != true

        all.filter { it.item.booking != null && !it.item.booking.date.isAfter(today) }
            .minByOrNull { it.item.booking!!.date }
            ?.let { first ->
                val booking = first.item.booking!!
                val sameVisit = all.filter { it.item.booking?.confirmation == booking.confirmation }
                return AgentAction.VisitDay(
                    sameVisit.map { it.rule },
                    booking,
                    situation.shops.firstOrNull { it.id == booking.shopId },
                )
            }

        all.firstOrNull { it.applied.basis == AppliedInterval.Basis.WARNING && it.item.booking == null && awake(it) }?.let {
            val companions = all.filter { row -> row !== it && row.item.booking == null && awake(row) &&
                row.dueDate?.isAfter(today.plusDays(TOGETHER_DAYS)) == false }
            return propose(it, situation, false, companions)
        }

        all.filter { s -> s.applied.basis != AppliedInterval.Basis.WARNING && !s.item.baselineCheck && s.item.booking != null && awake(s) && s.dueDate?.isBefore(s.item.booking.date) == true }
            .minByOrNull { it.dueDate!! }
            ?.let { late ->
                val sameVisit = all.filter { it !== late && it.item.booking?.confirmation == late.item.booking?.confirmation }
                return propose(late, situation, reschedule = true, together = sameVisit)
            }

        all.filter { s -> s.item.booking == null && awake(s) && s.dueDate?.isAfter(today.plusDays(BOOKING_MAX_DAYS)) == false }
            .minByOrNull { it.dueDate!! }
            ?.let { soon ->
                val togetherUntil = soon.dueDate!!.plusDays(TOGETHER_DAYS)
                val together = all.filter { s ->
                    s !== soon && s.item.booking == null && awake(s) && s.dueDate?.isAfter(togetherUntil) == false
                }
                return propose(soon, situation, reschedule = false, together = together)
            }

        return AgentAction.AllGood
    }

    private fun propose(status: Status, situation: Situation, reschedule: Boolean, together: List<Status>): AgentAction {
        val due = status.dueDate!!
        val unavailable = (listOf(status) + together).mapNotNull { it.item.cancelledShop }.toSet()
        val candidates = situation.copy(shops = situation.shops.filterNot { it.id in unavailable })
        val options = options(due, candidates, status.applied.basis == AppliedInterval.Basis.WARNING || status.item.baselineCheck)
        if (options.isEmpty()) return AgentAction.TooLate(status.rule, due)
        return AgentAction.Propose(status.rule, status.applied, due, options, reschedule, together.map { it.rule })
    }

    enum class Arrival { ON_TIME, OFFER_MOVE, MOVE_ONLY }

    fun arrival(delayMinutes: Long) = when {
        delayMinutes > 30 -> Arrival.MOVE_ONLY
        delayMinutes > 10 -> Arrival.OFFER_MOVE
        else -> Arrival.ON_TIME
    }

    /** 두 지점 사이 직선거리(km, 하버사인 공식). */
    fun distanceKm(place: Place, shop: Shop): Double {
        val r = 6371.0
        val dLat = Math.toRadians(shop.lat - place.lat)
        val dLon = Math.toRadians(shop.lon - place.lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(place.lat)) * cos(Math.toRadians(shop.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
