package app.pitstop.agent

import java.time.LocalDate

/** 시동을 켜고 끈 한 번의 주행. */
data class Trip(val date: LocalDate, val km: Double, val minutes: Int, val brakesPerMinute: Double? = null, val congestion: Double? = null, val downhill: Boolean? = null) {
    /** 평균 속도(km/h). 시간이 0분이면 알 수 없다. */
    val averageKmh: Double? get() = if (minutes > 0) km / (minutes / 60.0) else null
}

/** 최근 주행에서 읽어 낸 특징과, 그 특징으로 판단한 가혹 조건. */
data class DrivingProfile(
    val tripCount: Int,
    val shortTripShare: Double?,
    val slowTripShare: Double?,
    val detected: Set<SevereCondition>,
    val scores: Map<SevereCondition, Double>,
)

/**
 * 주행 기록으로 가혹 조건을 판단한다.
 * - 짧은 거리 기준 8 km: 현대차 취급설명서(미국판)의 "8 km 미만 짧은 거리 반복 주행"
 * - 10년·150,000 km: 같은 미국판의 "10년 또는 150,000 km 이상 운행 시 가혹 조건 주기"
 * - 저속 기준 20 km/h와 "반복"을 절반 이상으로 본 것은 설명서에 수치가 없어 앱이 정한 값이다.
 */
object DrivingAnalyzer {
    const val SHORT_TRIP_KM = 8.0
    const val SLOW_TRIP_KMH = 20.0
    const val REPEATED_SHARE = 0.5
    const val MIN_TRIPS = 10
    private const val WINDOW_DAYS = 30L
    private const val OLD_VEHICLE_KM = 150_000.0
    private const val OLD_VEHICLE_YEARS = 10

    fun analyze(trips: List<Trip>, today: LocalDate, currentKm: Double, modelYear: Int?): DrivingProfile {
        val recent = trips.filter { it.date in today.minusDays(WINDOW_DAYS)..today }.sortedBy { it.date }
        fun score(test: (Trip) -> Boolean?): Double? {
            val hits = recent.mapNotNull(test)
            if (hits.size < MIN_TRIPS) return null
            return hits.drop(1).fold(if (hits.first()) 1.0 else 0.0) { previous, hit ->
                0.85 * previous + if (hit) 0.15 else 0.0
            }
        }
        val scores = buildMap {
            score { it.downhill }?.let { put(SevereCondition.MOUNTAIN, it) }
            score { it.km < SHORT_TRIP_KM }?.let { put(SevereCondition.SHORT_TRIPS, it) }
            score { trip -> trip.congestion?.let { it >= 0.5 } ?: trip.averageKmh?.let { it < SLOW_TRIP_KMH } }
                ?.let { put(SevereCondition.CONGESTION, it) }
            score { trip -> trip.brakesPerMinute?.let { it >= 1.5 } }?.let { put(SevereCondition.STOP_AND_GO, it) }
        }
        val shortShare = recent.shareOf { it.km < SHORT_TRIP_KM }
        val timed = recent.filter { it.averageKmh != null }
        val slowShare = timed.shareOf { it.averageKmh!! < SLOW_TRIP_KMH }
        val detected = buildSet {
            addAll(scores.filterValues { it >= REPEATED_SHARE }.keys)
            val old = modelYear?.let { today.year - it >= OLD_VEHICLE_YEARS } == true
            if (old || currentKm >= OLD_VEHICLE_KM) add(SevereCondition.OLD_VEHICLE)
        }
        return DrivingProfile(recent.size, shortShare, slowShare, detected, scores)
    }

    private fun List<Trip>.shareOf(predicate: (Trip) -> Boolean): Double? =
        if (size < MIN_TRIPS) null else count(predicate).toDouble() / size
}

/**
 * Vehicle SDK의 "이번 주행" 거리(m)·시간(분) 알림으로 주행 하나가 끝난 것을 알아낸다.
 * 새 주행이 시작되면 값이 0 근처로 돌아가므로, 거리가 줄어드는 순간 직전 값을 끝난 주행으로 본다.
 */
class TripTracker {
    private var km = 0.0
    private var minutes = 0
    /** 시간이 거리보다 먼저 0으로 돌아가면 직전 주행의 시간을 여기 남겨 둔다. */
    private var finishedMinutes: Int? = null
    /** 거리가 먼저 0으로 돌아갔으면, 뒤따르는 시간 초기화는 이미 끝난 주행의 것이다. */
    private var timeResetPending = false

    private var previousPedal: Boolean? = null
    private var brakeCount = 0
    private var observedBrake = false

    fun onBrake(pressed: Boolean?) {
        if (pressed != null) {
            observedBrake = true
            if (pressed && previousPedal == false) brakeCount++
        }
        previousPedal = pressed
    }

    private var downhill: Boolean? = null
    private var congestion: Double? = null

    fun onGuidance(downhillPassed: Boolean?, congestionShare: Double?) {
        if (downhillPassed != null) downhill = downhill == true || downhillPassed
        if (congestionShare != null) congestion = congestionShare.coerceIn(0.0, 1.0)
    }

    fun onMinutes(value: Int) {
        if (value < minutes) {
            if (timeResetPending) timeResetPending = false else finishedMinutes = minutes
        }
        minutes = value
    }

    /** 주행 하나가 끝났으면 그 주행을 돌려준다. */
    fun onMeters(value: Double, date: LocalDate): Trip? {
        val newKm = value / 1000
        var finished: Trip? = null
        if (newKm + RESET_TOLERANCE_KM < km) {
            val duration = finishedMinutes ?: minutes
            val rate = if (observedBrake && duration > 0) brakeCount.toDouble() / duration else null
            if (km >= MIN_TRIP_KM) finished = Trip(date, km, duration, rate, congestion, downhill)
            congestion = null
            downhill = null
            brakeCount = 0
            observedBrake = false
            timeResetPending = finishedMinutes == null
            finishedMinutes = null
        }
        km = newKm
        return finished
    }

    private companion object {
        const val RESET_TOLERANCE_KM = 0.05
        const val MIN_TRIP_KM = 0.3
    }
}

/** 차대번호 10번째 자리(국제 표준 ISO 3779 연식 부호)로 연식을 읽는다. 표준과 다르면 null. */
fun modelYearFromVin(vin: String?, today: LocalDate): Int? {
    val code = vin?.trim()?.takeIf { it.length == 17 }?.get(9)?.uppercaseChar() ?: return null
    val index = YEAR_CODES.indexOf(code).takeIf { it >= 0 } ?: return null
    return generateSequence(1980 + index) { it + 30 }.takeWhile { it <= today.year + 1 }.lastOrNull()
}

private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"
