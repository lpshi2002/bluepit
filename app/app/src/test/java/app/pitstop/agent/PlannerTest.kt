package app.pitstop.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class PlannerTest {
    // 2026-11-02은 월요일이다.
    private val today = LocalDate.of(2026, 11, 2)
    private val currentKm = 32_000.0
    private val tucson = ScheduleCatalog.spec("nx4-t16")!!
    private val oilRule = tucson.rule("engine-oil")!!

    /** 엔진오일만 있는 상황. 남은 거리 1,100 km. */
    private val oil = MaintenanceItem("engine-oil", currentKm - 8_900, today.minusMonths(8))

    private fun situation(
        items: List<MaintenanceItem> = listOf(oil),
        km: Double = currentKm,
        on: LocalDate = today,
        samples: List<OdometerSample> = emptyList(),
        conditions: Set<SevereCondition> = emptySet(),
        spec: VehicleSpec = tucson,
    ) = Situation(on, km, samples, spec, items, conditions, DemoData.fallbackPlaces, DemoData.shops, DemoData.availability)

    @Test
    fun `기록이 7일보다 짧으면 하루 평균을 내지 않는다`() {
        val samples = listOf(OdometerSample(today.minusDays(3), 31_000.0), OdometerSample(today, 32_000.0))
        assertNull(Planner.dailyKm(samples, today))
    }

    @Test
    fun `하루 평균은 최근 기록의 처음과 끝으로 계산한다`() {
        val samples = listOf(OdometerSample(today.minusDays(10), 31_000.0), OdometerSample(today, 32_000.0))
        assertEquals(100.0, Planner.dailyKm(samples, today)!!, 0.001)
    }

    @Test
    fun `교체 예정일은 거리 기준과 기간 기준 중 빠른 날이다`() {
        val interval = oilRule.normal
        // 남은 1,100km ÷ 하루 40km = 28일 뒤. 기간 기준은 4개월 뒤라 거리 기준이 먼저다.
        assertEquals(today.plusDays(28), Planner.dueDate(oil, interval, currentKm, today, 40.0))
        // 거의 달리지 않으면 기간 기준을 따른다.
        assertEquals(today.plusMonths(4), Planner.dueDate(oil, interval, currentKm, today, 1.0))
    }

    @Test
    fun `주행거리로만 관리하는 항목은 주행이 없으면 예측하지 않는다`() {
        val brake = MaintenanceItem("brake-fluid", currentKm - 10_000, today.minusYears(1))
        assertNull(Planner.dueDate(brake, Interval(50_000, null), currentKm, today, 0.0))
    }

    @Test
    fun `가혹 조건이면 엔진오일 주기를 가혹 조건 주기로 바꾼다`() {
        val normal = Planner.applied(oilRule, oil, emptySet())
        assertEquals(AppliedInterval.Basis.NORMAL, normal.basis)
        assertEquals(Interval(10_000, 12), normal.interval)

        val severe = Planner.applied(oilRule, oil, setOf(SevereCondition.SHORT_TRIPS))
        assertEquals(AppliedInterval.Basis.SEVERE, severe.basis)
        assertEquals(Interval(5_000, 6), severe.interval)
        assertEquals(setOf(SevereCondition.SHORT_TRIPS), severe.because)
    }

    @Test
    fun `가혹 조건 주기가 없는 항목은 통상 조건을 유지한다`() {
        val brake = tucson.rule("brake-fluid")!!
        val item = MaintenanceItem("brake-fluid", 0.0, today)
        assertEquals(AppliedInterval.Basis.NORMAL, Planner.applied(brake, item, setOf(SevereCondition.SHORT_TRIPS)).basis)
    }

    @Test
    fun `냉각수는 첫 교체 전에는 최초 주기를, 교체 후에는 통상 주기를 쓴다`() {
        val coolant = tucson.rule("coolant")!!
        val never = MaintenanceItem("coolant", 0.0, today.minusYears(3), replacedBefore = false)
        assertEquals(Interval(200_000, 120), Planner.applied(coolant, never, emptySet()).interval)
        assertEquals(Interval(40_000, 24), Planner.applied(coolant, never.serviced(50_000.0, today), emptySet()).interval)
    }

    @Test
    fun `짧은 주행이 감지되면 여유 있던 엔진오일 교체를 제안한다`() {
        val item = MaintenanceItem("engine-oil", currentKm - 4_400, today.minusMonths(4))
        assertEquals(AgentAction.AllGood, Planner.decide(situation(listOf(item))))

        val action = Planner.decide(situation(listOf(item), conditions = setOf(SevereCondition.SHORT_TRIPS))) as AgentAction.Propose
        assertEquals("engine-oil", action.rule.id)
        assertEquals(AppliedInterval.Basis.SEVERE, action.applied.basis)
    }

    @Test
    fun `월요일 예약은 5일 전까지만 된다`() {
        val nextMonday = today.plusDays(7)
        assertTrue(Planner.isBookable(nextMonday, today))
        assertTrue(!Planner.isBookable(nextMonday, nextMonday.minusDays(4)))
        assertTrue(Planner.isBookable(nextMonday.plusDays(1), nextMonday.minusDays(2)))
    }

    @Test
    fun `교체가 6주 안이면 예정일 1주 전쯤 가능한 시간대로 제안한다`() {
        val schedule = listOf(
            Availability(setOf(DayOfWeek.SATURDAY), 10, PlaceKind.HOME),
            Availability(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), 18, PlaceKind.OFFICE),
        )
        val action = Planner.decide(situation().copy(availability = schedule)) as AgentAction.Propose
        val first = action.options.first()

        assertEquals(today.plusDays(28), action.dueDate)
        assertTrue(!action.reschedule)
        assertTrue(first.date.isBefore(action.dueDate))
        assertTrue(first.date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))
        // 토요일은 집 근처, 평일 저녁은 회사 근처 정비소다.
        val expectedShop = if (first.date.dayOfWeek == DayOfWeek.SATURDAY) "demo-bundang" else "demo-gangnam"
        assertEquals(expectedShop, first.shop.id)
    }

    @Test
    fun `예정일이 가까운 다른 소모품은 같은 날 함께 맡기자고 한다`() {
        // 엔진오일 28일 뒤, 타이어 위치 교환 40일 뒤, 브레이크액은 한참 뒤.
        val tire = MaintenanceItem("tire-rotation", currentKm - 8_400, today.minusMonths(5))
        val brake = MaintenanceItem("brake-fluid", currentKm - 10_000, today.minusYears(1))
        val action = Planner.decide(situation(listOf(oil, tire, brake))) as AgentAction.Propose
        assertEquals("engine-oil", action.rule.id)
        assertEquals(listOf("tire-rotation"), action.together.map { it.id })
    }

    @Test
    fun `예상보다 많이 달려 예약일 전에 교체 시점이 오면 앞당기자고 한다`() {
        val booking = Booking("demo-bundang", today.plusDays(26), 10, "DEMO")
        val bookedOil = oil.booked(booking)
        assertEquals(AgentAction.AllGood, Planner.decide(situation(listOf(bookedOil))))

        // 600km를 더 달려 남은 거리가 500km가 되면 예정일이 13일 뒤로 당겨진다.
        val action = Planner.decide(situation(listOf(bookedOil), km = currentKm + 600)) as AgentAction.Propose
        assertTrue(action.reschedule)
        assertTrue(action.options.all { it.date.isBefore(booking.date) })
    }

    @Test
    fun `교체 시점이 예약 가능한 가장 빠른 날보다 앞이면 전화 예약으로 넘긴다`() {
        assertTrue(Planner.decide(situation(km = currentKm + 1_050)) is AgentAction.TooLate)
    }

    @Test
    fun `예약일이 되면 같은 예약으로 묶인 소모품을 함께 안내한다`() {
        val booking = Booking("demo-bundang", today, 10, "DEMO")
        val tire = MaintenanceItem("tire-rotation", currentKm - 8_400, today.minusMonths(5)).booked(booking)
        val action = Planner.decide(situation(listOf(oil.booked(booking), tire))) as AgentAction.VisitDay
        assertEquals("demo-bundang", action.shop?.id)
        assertEquals(setOf("engine-oil", "tire-rotation"), action.rules.map { it.id }.toSet())
    }

    @Test
    fun `나중에를 고르면 그날까지 다시 묻지 않는다`() {
        val snoozed = oil.snoozed(today.plusDays(3))
        assertEquals(AgentAction.AllGood, Planner.decide(situation(listOf(snoozed))))
        assertTrue(Planner.decide(situation(listOf(snoozed), on = today.plusDays(3))) is AgentAction.Propose)
    }

    @Test
    fun `정비를 마치면 기준점이 리셋되어 다시 여유가 생긴다`() {
        val done = oil.booked(Booking("demo-bundang", today, 10, "DEMO")).serviced(currentKm, today)
        assertEquals(AgentAction.AllGood, Planner.decide(situation(listOf(done))))
    }

    @Test
    fun `전기차에는 엔진오일이 없고 감속기 오일이 있다`() {
        val ioniq5 = ScheduleCatalog.spec("ne1")!!
        assertNull(ioniq5.rule("engine-oil"))
        assertEquals(Interval(60_000, null), ioniq5.rule("reduction-gear-oil")!!.normal)
    }

    @Test
    fun `엔진차는 모두 엔진오일 통상·가혹 주기가 있고 가혹 주기가 더 짧다`() {
        ScheduleCatalog.specs.filter { it.powertrain != Powertrain.EV }.forEach { spec ->
            val rule = spec.rule("engine-oil")!!
            assertTrue(spec.id, rule.severe!!.km!! < rule.normal.km!!)
        }
        assertEquals(Interval(20_000, 12), ScheduleCatalog.spec("lx2-d22")!!.rule("engine-oil")!!.normal)
    }
}
