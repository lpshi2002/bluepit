package app.pitstop

import app.pitstop.agent.AgentAction
import app.pitstop.agent.AppliedInterval
import app.pitstop.agent.Booking
import app.pitstop.agent.BookingOption
import app.pitstop.agent.BookingService
import app.pitstop.agent.DemoBookingService
import app.pitstop.agent.DemoData
import app.pitstop.agent.DrivingAnalyzer
import app.pitstop.agent.DrivingProfile
import app.pitstop.agent.ItemRule
import app.pitstop.agent.MaintenanceItem
import app.pitstop.agent.OdometerSample
import app.pitstop.agent.Place
import app.pitstop.agent.Planner
import app.pitstop.agent.Powertrain
import app.pitstop.agent.ScheduleCatalog
import app.pitstop.agent.SevereCondition
import app.pitstop.agent.Shop
import app.pitstop.agent.Situation
import app.pitstop.agent.TripTracker
import app.pitstop.agent.VehicleSpec
import app.pitstop.agent.modelYearFromVin
import app.pitstop.navi.NaviClient
import app.pitstop.vehicle.VehicleSource
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.time.LocalDate

/** 차량·내비 데이터를 모아 Planner에 넘기고, 사용자의 답을 상태에 반영한다. */
class AgentViewModel(app: Application) : AndroidViewModel(app) {

    data class ItemRow(
        val rule: ItemRule,
        val item: MaintenanceItem,
        val applied: AppliedInterval,
        val remainingKm: Double?,
        val dueDate: LocalDate?,
    )

    /** 차량 SDK로 알아낸 차량 정보. */
    data class VehicleInfo(val vin: String?, val modelYear: Int?, val energy: Energy?) {
        enum class Energy(val label: String) { ENGINE("엔진차"), EV("전기차"), BOTH("연료·배터리 둘 다 (하이브리드 계열)"), UNKNOWN("판별 불가") }
    }

    data class UiState(
        val today: LocalDate,
        val currentKm: Double?,
        val kmFromVehicle: Boolean,
        val placesFromNavi: Boolean,
        val dailyKm: Double,
        val usingDefaultDailyKm: Boolean,
        val vehicle: VehicleInfo,
        val spec: VehicleSpec?,
        val profile: DrivingProfile?,
        val conditions: Set<SevereCondition>,
        val declared: Set<SevereCondition>,
        val action: AgentAction?,
        val optionIndex: Int,
        val items: List<ItemRow>,
        val notice: String?,
        val setupDone: Boolean,
        val demo: Boolean,
        val partnerDemo: Boolean,
        val low: Boolean,
    )

    private val realStore = AgentStore(app, "history-v3")
    private val demoStore = AgentStore(app, "demo-v1")
    private val mode = app.getSharedPreferences("pitstop-mode", 0)
    init {
        if (!mode.contains("migrated")) {
            val legacy = AgentStore(app).load()
            if (legacy.setupDone && legacy.demo && !demoStore.load().setupDone) demoStore.save(legacy)
            mode.edit().putBoolean("migrated", true).apply()
        }
    }
    private var store = if (mode.getBoolean("demo", false)) demoStore else realStore
    private val bookingService: BookingService = DemoBookingService()
    private val main = Handler(Looper.getMainLooper())
    private val tripTracker = TripTracker()

    private var saved = store.load()
    private val lowFluids = mutableSetOf<Boolean>()
    var canInteract by mutableStateOf(false)
        private set
    var pending by mutableStateOf<BookingOption?>(null)
        private set
    private var pendingRules = emptySet<String>()
    private var undoItems = emptyList<MaintenanceItem>()
    var undoBooking by mutableStateOf<Booking?>(null)
        private set
    private var undoDeadline = 0L
    private var pendingDeadline = 0L

    fun allowInteraction(allowed: Boolean) {
        canInteract = allowed
        if (!allowed) { pending = null; checkedBooking = null }
    }
    private var baseKm: Double? = null
    private var kmFromVehicle = false
    private var vehicleConnected = false
    private var vin: String? = null
    private var energy: VehicleInfo.Energy? = null
    private var naviPlaces: List<Place> = emptyList()
    private var optionIndex = 0
    /** 지금 보여 주는 제안. 제안이 바뀔 때만 "다른 시간" 선택을 처음으로 돌린다. */
    private var proposalKey: Pair<String, Boolean>? = null
    private var notice: String? = null

    var state by mutableStateOf(buildState())
        private set

    private val vehicleSource = VehicleSource(app, object : VehicleSource.Listener {
        override fun onOdometerKm(km: Double) = post { onBaseKm(km, fromVehicle = true) }

        override fun onBrake(pressed: Boolean?) = post { tripTracker.onBrake(pressed) }
        override fun onFluid(ev: Boolean, low: Boolean) = post {
            val changed = if (low) lowFluids.add(ev) else lowFluids.remove(ev)
            if (changed) refresh()
        }

        override fun onTripMeters(meters: Double) = post {
            tripTracker.onMeters(meters, today)?.let { trip ->
                if (saved.demo) return@post
                saved = saved.copy(trips = (saved.trips + trip).takeLast(TRIP_KEEP_COUNT))
                refresh()
            }
        }

        override fun onTripMinutes(minutes: Int) = post { tripTracker.onMinutes(minutes) }

        override fun onVin(vin: String) = post {
            this@AgentViewModel.vin = vin
            refresh()
        }

        override fun onEnergySource(hasFuel: Boolean, hasBattery: Boolean) = post {
            energy = when {
                hasFuel && hasBattery -> VehicleInfo.Energy.BOTH
                hasBattery -> VehicleInfo.Energy.EV
                hasFuel -> VehicleInfo.Energy.ENGINE
                else -> VehicleInfo.Energy.UNKNOWN
            }
            Log.i(TAG, "energy source: fuel=$hasFuel battery=$hasBattery")
            start()
        }

        override fun onOdometerUnavailable(e: Exception) {
            Log.w(TAG, "Odometer unavailable", e)
            post { if (baseKm == null) onBaseKm(DemoData.FALLBACK_ODOMETER_KM, fromVehicle = false) }
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "Vehicle SDK error", e)
        }
    })

    private var timingBooking: Booking? = null
    private var checkedBooking: Booking? = null
    var delayed by mutableStateOf<Pair<Booking, Planner.Arrival>?>(null)
        private set

    private val navi = NaviClient(app, { seconds -> post {
        val booking = timingBooking
        timingBooking = null
        if (booking != null && booking == (state.action as? AgentAction.VisitDay)?.booking) {
            val eta = java.time.LocalDateTime.now().plusSeconds(seconds.toLong())
            val delay = java.time.Duration.between(booking.date.atTime(booking.hour, booking.minute), eta).toMinutes()
            showDelay(booking, delay)
        }
    } }, { code -> post { notice = "내비 응답: $code. 내비에서 경로를 확인해 주세요."; refresh() } }) { places -> post { naviPlaces = places; refresh() } }

    init {
        vehicleConnected = vehicleSource.start()
        if (!vehicleConnected && baseKm == null) onBaseKm(DemoData.FALLBACK_ODOMETER_KM, fromVehicle = false)
        // 동력계 응답이 오지 않으면 기본 차종으로 시작한다.
        main.postDelayed({
            if (baseKm == null) onBaseKm(DemoData.FALLBACK_ODOMETER_KM, fromVehicle = false)
            if (energy == null) {
                energy = VehicleInfo.Energy.UNKNOWN
                start()
            }
        }, ENERGY_TIMEOUT_MS)
        navi.start()
    }

    private val today get() = LocalDate.now().plusDays(saved.demoDayOffset)
    private val currentKm get() = if (saved.demo) DemoData.FALLBACK_ODOMETER_KM + saved.demoExtraKm else baseKm
    private val spec get() = ScheduleCatalog.spec(saved.specId)

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun onBaseKm(km: Double, fromVehicle: Boolean) {
        // 차량 SDK는 값이 같아도 몇 초마다 알려 준다. 바뀐 값만 반영한다.
        if (km == baseKm && fromVehicle == kmFromVehicle) return
        baseKm = km
        kmFromVehicle = fromVehicle
        start()
    }

    /**
     * 주행거리와 차종이 정해지면 소모품 기록을 만든다. 사용자가 차종을 고르지 않았으면
     * 차량 SDK가 동력계를 알려 줄 때까지 기다렸다가 그에 맞는 기본 차종을 고른다.
     */
    private fun start() {
        val km = currentKm ?: return refresh()
        if (saved.specId == null) {
            if (vehicleConnected && energy == null) return refresh()
            val id = if (energy == VehicleInfo.Energy.EV) DemoData.DEFAULT_EV_SPEC else DemoData.DEFAULT_ENGINE_SPEC
            saved = saved.copy(specId = id)
        }
        if (saved.setupDone) {
            val missing = spec!!.rules.filter { rule -> saved.items.none { it.ruleId == rule.id } }
            if (missing.isNotEmpty()) saved = saved.copy(items = saved.items + missing.map {
                Planner.history(it, null, km, today, Planner.DEFAULT_DAILY_KM, modelYearFromVin(vin, today))
            })
        }
        recordSample()
        refresh()
    }

    // --- 차량·주행 환경 설정 ---

    /** 차종을 바꾸면 소모품 목록이 달라지므로 교체 기록을 새로 만든다. */
    fun selectSpec(id: String) {
        val newSpec = ScheduleCatalog.spec(id) ?: return
        val km = currentKm ?: return
        if (!canInteract || saved.specId == id) return
        pending = null
        undoBooking = null
        saved = saved.copy(specId = id, setupDone = saved.demo, demoLow = false,
            items = if (saved.demo) DemoData.initialItems(newSpec, km, today) else emptyList())
        notice = "${newSpec.label} 기준으로 바꿨어요. 근거: ${newSpec.manual} 취급설명서"
        refresh()
    }

    fun toggleDeclared(condition: SevereCondition) {
        val declared = if (condition in saved.declared) saved.declared - condition else saved.declared + condition
        saved = saved.copy(declared = declared)
        notice = null
        refresh()
    }

    // --- 사용자 응답 ---

    fun finishSetup(answers: Map<String, Long?>) {
        val vehicle = spec ?: return
        val km = currentKm ?: return
        if (!canInteract || saved.setupDone) return
        val daily = Planner.dailyKm(saved.samples, today) ?: Planner.DEFAULT_DAILY_KM
        saved = saved.copy(setupDone = true, items = vehicle.rules.map {
            Planner.history(it, answers[it.id], km, today, daily, state.vehicle.modelYear)
        })
        refresh()
    }

    fun accept() {
        if (!canInteract) return
        val action = state.action as? AgentAction.Propose ?: return
        pending = action.options.getOrNull(state.optionIndex) ?: return
        pendingRules = (listOf(action.rule) + action.together).map { it.id }.toSet()
        pendingDeadline = android.os.SystemClock.elapsedRealtime() + 8000
        val expires = pendingDeadline
        main.postDelayed({ if (pendingDeadline == expires) pending = null }, 8000)
    }

    fun dismissSummary() { pending = null }

    fun confirm() {
        val option = pending ?: return
        pending = null
        val action = state.action as? AgentAction.Propose ?: return
        val rules = (listOf(action.rule) + action.together).map { it.id }.toSet()
        if (!canInteract || android.os.SystemClock.elapsedRealtime() >= pendingDeadline ||
            option !in action.options || rules != pendingRules) return
        val booking = bookingService.book(option)
        undoItems = state.items.filter { it.rule.id in rules }.map { it.item }
        saved = saved.copy(items = saved.items.filterNot { it.ruleId in rules } + undoItems.map { it.booked(booking) })
        undoBooking = booking
        undoDeadline = android.os.SystemClock.elapsedRealtime() + 10000
        main.postDelayed({ if (undoBooking == booking) undoBooking = null }, 10000)
        notice = if (option.shop.partner) "제휴 가정 시연 예약을 저장했어요." else
            "예약 계획을 저장했어요. 080-600-6000 또는 마이현대에서 직접 접수해 주세요."
        if (option.date == today && option.detourMinutes != null) {
            val result = navi.addWaypoint(option.shop)
            notice += if (result.isSuccess) " 경유지 추가를 요청했어요." else " 내비가 경유지 요청을 받지 못했어요."
        }
        refresh()
    }

    fun undo() {
        val booking = undoBooking ?: return
        if (!canInteract || android.os.SystemClock.elapsedRealtime() >= undoDeadline) return
        val before = undoItems.associateBy { it.ruleId }
        saved = saved.copy(items = saved.items.map { if (it.booking == booking) before[it.ruleId] ?: it else it })
        undoBooking = null
        notice = "앱 안의 예약 계획을 되돌렸어요."
        if (booking.date == today && booking.hour == 18 && booking.minute == 10) removeWaypoint(booking)
        refresh()
    }

    private fun removeWaypoint(booking: Booking) {
        val shop = DemoData.shops.firstOrNull { it.id == booking.shopId } ?: return
        navi.removeMatchingWaypoint(shop) { message -> post { notice = message; refresh() } }
    }

    fun cancel(booking: Booking, removeStop: Boolean = false, byShop: Boolean = false) {
        if (!canInteract) return
        saved = saved.copy(items = saved.items.map { if (it.booking == booking) it.copy(booking = null, cancelledShop = if (byShop) booking.shopId else null) else it })
        undoBooking = null
        if (removeStop) removeWaypoint(booking)
        notice = "앱 안의 계획을 취소했어요. 공식 채널 예약은 080-600-6000에서 확인해 주세요."
        refresh()
    }

    fun nextOption() {
        optionIndex += 1
        state = buildState()
    }

    fun snooze() {
        val rule = when (val action = state.action) {
            is AgentAction.Propose -> action.rule
            is AgentAction.TooLate -> action.rule
            else -> return
        }
        ensureItem(rule.id)
        updateItem(rule.id) { it.snoozed(today.plusDays(SNOOZE_DAYS)) }
        notice = "${SNOOZE_DAYS}일 뒤에 다시 물어볼게요."
        refresh()
    }

    fun complete(rules: List<ItemRule>) {
        val km = currentKm ?: return
        undoBooking = null
        rules.forEach { rule -> updateItem(rule.id) { it.serviced(km, today) } }
        saved = saved.copy(items = saved.items.filterNot { it.ruleId in setOf("oil-level", "brake-fluid-level") && it.booking == null },
            demoLow = saved.demoLow && rules.none { it.id in setOf("oil-level", "brake-fluid-level") })
        notice = "${rules.joinToString(", ") { it.name }}을 기록했어요. 다음 주기를 다시 계산합니다."
        refresh()
    }

    fun checkArrival(visit: AgentAction.VisitDay) {
        val b = visit.booking
        val shop = visit.shop ?: return
        if (checkedBooking == b || b.date != today || b.hour != 18 || b.minute != 10 || !canInteract || saved.demo) return
        checkedBooking = b
        timingBooking = b
        navi.requestTravelTime(shop).onFailure { timingBooking = null }
    }

    fun demoDelay(minutes: Long) {
        val b = (state.action as? AgentAction.VisitDay)?.booking ?: return
        if (canInteract && saved.demo) showDelay(b, minutes)
    }

    private fun showDelay(booking: Booking, minutes: Long) {
        val result = Planner.arrival(minutes)
        delayed = if (result == Planner.Arrival.ON_TIME) null else booking to result
        notice = if (result == Planner.Arrival.ON_TIME) "예약 시간에 맞춰 이동할 수 있어요."
            else "도착 예정 ${booking.date.atTime(booking.hour, booking.minute).plusMinutes(minutes).toLocalTime()} · 다른 날로 옮길까요?"
        refresh()
    }

    fun moveLater(booking: Booking) {
        if (!canInteract) return
        saved = saved.copy(items = saved.items.map {
            if (it.booking == booking) it.copy(booking = null, snoozedUntil = null) else it
        }, partnerDemo = false)
        delayed = null
        undoBooking = null
        removeWaypoint(booking)
        notice = "기존 시연 계획을 해제했어요. 일반 정비소의 다음 날짜를 확인해 주세요."
        refresh()
    }

    fun routeTo(shop: Shop) = reportNavi(navi.routeTo(shop), "${shop.name}로 안내를 요청했어요.")

    fun addWaypoint(shop: Shop) = reportNavi(navi.addWaypoint(shop), "${shop.name}을 경유지로 넣었어요.")

    private fun reportNavi(result: Result<Unit>, success: String) {
        notice = result.fold({ success }, { "내비 요청 실패: ${it.message ?: it::class.java.simpleName}" })
        state = buildState()
    }

    // --- 시연용 조작 ---

    /** 시계를 하루씩 넘기며, 하루 km를 tripsPerDay번에 나눠 평균 kmh로 달린 것으로 기록한다. */
    fun demoDrive(days: Int, kmPerDay: Double, tripsPerDay: Int, kmh: Double) {
        if (!saved.demo || !canInteract) return
        repeat(days) {
            saved = saved.copy(
                demoDayOffset = saved.demoDayOffset + 1,
                demoExtraKm = saved.demoExtraKm + kmPerDay,
                trips = (saved.trips + DemoData.trips(today.plusDays(1), kmPerDay, tripsPerDay, kmh, saved.trips.size)).takeLast(TRIP_KEEP_COUNT),
            )
            recordSample()
        }
        notice = null
        refresh()
    }

    /** 시연 기록만 지운다. 고른 차종과 직접 알려 준 주행 환경은 남긴다. */
    fun switchDemo(enable: Boolean) {
        if (!canInteract) return
        pending = null
        undoBooking = null
        store = if (enable) demoStore else realStore
        mode.edit().putBoolean("demo", enable).apply()
        saved = store.load()
        notice = null
        if (enable && !saved.setupDone) demoReset() else start()
    }

    fun demoReset() {
        if (!canInteract || store !== demoStore) return
        val id = saved.specId ?: DemoData.DEFAULT_ENGINE_SPEC
        saved = AgentStore.Saved(specId = id, setupDone = true, demo = true,
            items = DemoData.initialItems(ScheduleCatalog.spec(id)!!, DemoData.FALLBACK_ODOMETER_KM, LocalDate.now()))
        pending = null
        undoBooking = null
        notice = null
        start()
    }

    fun demoIgnition() {
        demoReset()
        if (!canInteract || !saved.demo) return
        val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
        val vehicle = ScheduleCatalog.spec(DemoData.DEFAULT_ENGINE_SPEC)!!
        val km = currentKm ?: return
        val items = DemoData.initialItems(vehicle, km, monday).map {
            if (it.ruleId == "engine-oil") it.copy(lastServiceKm = km - 4000, lastServiceDate = monday.minusMonths(3))
            else if (it.ruleId == "tire-rotation") it.copy(lastServiceKm = km - 8400) else it
        }
        saved = saved.copy(specId = vehicle.id, items = items, demoDayOffset = monday.toEpochDay() - LocalDate.now().toEpochDay(),
            trips = List(14) { app.pitstop.agent.Trip(monday.minusDays(7L - it / 2), 20.0, 70) })
        notice = "시연 · 월요일 시동 직후"
        refresh()
    }

    fun togglePartner() {
        if (saved.demo) { saved = saved.copy(partnerDemo = !saved.partnerDemo); refresh() }
    }

    fun demoWarning() {
        if (saved.demo) { saved = saved.copy(demoLow = !saved.demoLow); refresh() }
    }

    private fun ensureItem(id: String) {
        if (saved.items.none { it.ruleId == id }) state.items.firstOrNull { it.rule.id == id }?.let {
            saved = saved.copy(items = saved.items + it.item)
        }
    }

    private fun updateItem(ruleId: String, change: (MaintenanceItem) -> MaintenanceItem) {
        saved = saved.copy(items = saved.items.map { if (it.ruleId == ruleId) change(it) else it })
    }

    private fun recordSample() {
        val km = currentKm ?: return
        val samples = saved.samples.filter { it.date != today && it.date.isAfter(today.minusDays(SAMPLE_KEEP_DAYS)) }
        saved = saved.copy(samples = samples + OdometerSample(today, km))
    }

    private fun refresh() {
        store.save(saved)
        state = buildState()
    }

    private fun places() = DemoData.fallbackPlaces.map { fallback -> naviPlaces.firstOrNull { it.kind == fallback.kind } ?: fallback }

    private fun buildState(): UiState {
        val km = currentKm
        val spec = spec
        val measuredDaily = Planner.dailyKm(saved.samples, today)
        val vehicle = VehicleInfo(vin, modelYearFromVin(vin, today), energy)
        val profile = km?.let { DrivingAnalyzer.analyze(saved.trips, today, it, vehicle.modelYear) }
        val conditions = profile?.detected.orEmpty() + saved.declared
        val low = if (saved.demo) saved.demoLow else (spec?.powertrain == Powertrain.EV) in lowFluids
        val situation = if (km != null && spec != null && saved.setupDone) {
            Situation(today, km, saved.samples, spec, saved.items, conditions, places(), DemoData.shops, DemoData.availability, profile?.scores.orEmpty(), saved.partnerDemo, low)
        } else {
            null
        }
        val action = situation?.let(Planner::decide)

        val proposal = action as? AgentAction.Propose
        val key = proposal?.let { it.rule.id to it.reschedule }
        if (key != proposalKey) {
            proposalKey = key
            optionIndex = 0
        }
        val options = proposal?.options.orEmpty()
        return UiState(
            today = today,
            currentKm = km,
            kmFromVehicle = kmFromVehicle,
            placesFromNavi = naviPlaces.isNotEmpty(),
            dailyKm = measuredDaily ?: Planner.DEFAULT_DAILY_KM,
            usingDefaultDailyKm = measuredDaily == null,
            vehicle = vehicle,
            spec = spec,
            profile = profile,
            conditions = conditions,
            declared = saved.declared,
            action = action,
            optionIndex = optionIndex.coerceAtMost((options.size - 1).coerceAtLeast(0)),
            items = situation?.let { s ->
                Planner.statuses(s).map {
                    ItemRow(it.rule, it.item, it.applied, Planner.remainingKm(it.item, it.applied.interval, s.currentKm), it.dueDate)
                }.sortedBy { it.dueDate ?: LocalDate.MAX }
            }.orEmpty(),
            notice = notice, setupDone = saved.setupDone, demo = saved.demo, partnerDemo = saved.partnerDemo, low = low,
        )
    }

    override fun onCleared() {
        main.removeCallbacksAndMessages(null)
        vehicleSource.stop()
        navi.stop()
    }

    /** 감지한 동력계와 고른 차종이 맞지 않으면 알려 주기 위해 쓴다. */
    fun specMatchesVehicle(spec: VehicleSpec): Boolean = when (energy) {
        VehicleInfo.Energy.EV -> spec.powertrain == Powertrain.EV
        VehicleInfo.Energy.ENGINE -> spec.powertrain != Powertrain.EV
        else -> true
    }

    private companion object {
        const val TAG = "Pitstop"
        const val SNOOZE_DAYS = 3L
        const val SAMPLE_KEEP_DAYS = 60L
        const val TRIP_KEEP_COUNT = 300
        const val ENERGY_TIMEOUT_MS = 3_000L
    }
}
