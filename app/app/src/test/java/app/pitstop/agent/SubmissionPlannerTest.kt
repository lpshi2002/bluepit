package app.pitstop.agent

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SubmissionPlannerTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val tucson = ScheduleCatalog.spec("nx4-t16")!!
    private val oil = MaintenanceItem("engine-oil", 0.0, monday)
    private val sit = Situation(monday, 4000.0, emptyList(), tucson, listOf(oil), emptySet(),
        DemoData.fallbackPlaces, DemoData.shops, DemoData.availability)

    private fun interval(trips: List<Trip>): AppliedInterval {
        val profile = DrivingAnalyzer.analyze(trips, monday, 4000.0, null)
        return Planner.statuses(sit.copy(conditions = profile.detected, scores = profile.scores)).single().applied
    }

    @Test fun commuteWeekMatchesWeb() {
        val trips = (0 until 7).flatMap { DemoData.trips(monday.minusDays(6L-it), 40.0, 2, 35.0, it * 2) }
        val applied = interval(trips)
        assertEquals(AppliedInterval.Basis.ADJUSTED, applied.basis)
        assertEquals(Interval(9700, 12), applied.interval)
    }

    @Test fun cityWeekMatchesWeb() {
        val trips = (0 until 7).flatMap { DemoData.trips(monday.minusDays(6L-it), 20.0, 4, 15.0) }
        val applied = interval(trips)
        assertEquals(Interval(5000, 6), applied.interval)
        assertEquals(setOf(SevereCondition.SHORT_TRIPS, SevereCondition.STOP_AND_GO, SevereCondition.CONGESTION), applied.because)
    }

    @Test fun brakeSignalCountsRisingEdgesOnly() {
        val tracker = TripTracker()
        tracker.onMeters(15000.0, monday)
        tracker.onMinutes(2)
        repeat(3) { tracker.onBrake(false); tracker.onBrake(true); tracker.onBrake(true) }
        val trip = tracker.onMeters(0.0, monday)!!
        assertEquals(1.5, trip.brakesPerMinute!!, 0.0)
        assertEquals(setOf(SevereCondition.STOP_AND_GO), interval(List(10) { trip }).because)
        assertEquals(AppliedInterval.Basis.NORMAL, interval(List(9) { trip } + trip.copy(brakesPerMinute = null)).basis)
    }

    @Test fun newerObservationsCanReverseACondition() {
        val hit = Trip(monday, 5.0, 4)
        val miss = Trip(monday, 10.0, 10)
        assertTrue(SevereCondition.SHORT_TRIPS in interval(List(5) { miss } + List(5) { hit }).because)
        assertFalse(SevereCondition.SHORT_TRIPS in interval(List(5) { hit } + List(5) { miss }).because)
    }

    @Test fun warningsAreImmediateForEachPowertrain() {
        for ((spec, id) in listOf(tucson to "oil-level", ScheduleCatalog.spec("ne1")!! to "brake-fluid-level")) {
            val action = Planner.decide(sit.copy(spec = spec, items = emptyList(), lowFluid = true, partnerDemo = true)) as AgentAction.Propose
            assertEquals(id, action.rule.id)
            assertEquals(monday, action.dueDate)
            val first = action.options.first()
            assertEquals(monday, first.date)
            assertEquals(18, first.hour)
            assertEquals(10, first.minute)
            assertTrue(first.shop.partner && first.detourMinutes!! <= 5)
            assertEquals(AppliedInterval.Basis.WARNING, action.applied.basis)
        }
    }

    @Test fun publicSlotsRespectClosingAndBookingWindow() {
        for (offset in 0..49) for (o in Planner.options(monday.plusDays(offset.toLong()), sit)) {
            assertFalse(o.shop.partner)
            assertTrue(Planner.isBookable(o.date, monday))
            assertTrue(o.hour * 60 + o.minute <= 1080)
        }
        assertFalse(Planner.isBookable(monday.plusDays(43), monday))
        assertTrue(Planner.isBookable(monday.plusDays(42), monday))
        assertEquals(setOf(8 to 30, 12 to 0, 10 to 0), (3..42).flatMap {
            Planner.options(monday.plusDays(it.toLong()), sit)
        }.map { it.hour to it.minute }.toSet())
    }

    @Test fun longCommuteDetoursAreExcluded() {
        val far = Shop("far", "외곽", "", 36.0, 126.0, true)
        assertTrue(Planner.options(monday, sit.copy(shops = listOf(far), partnerDemo = true), true).isEmpty())
    }

    @Test fun conditionalTransmissionRulesMatchEachManual() {
        val expected = mapOf("nx4-t16" to setOf(1,4,5,6,7,8), "dn8-turbo" to ((1..9).toSet()+11),
            "sx2-hev" to setOf(4,5,6,7,8,10,11), "lx2-d22" to setOf(1,4,5,6,7,8,9,11),
            "ne1" to setOf(2,4,6,10))
        for ((id, numbers) in expected) {
            val r = ScheduleCatalog.spec(id)!!.rules.last()
            assertEquals(numbers, r.severeWhen.map { it.number }.toSet())
            assertEquals(if (id in setOf("sx2-hev", "ne1")) 120000 else 100000, r.severe!!.km)
            assertEquals(AppliedInterval.Basis.NONE, Planner.applied(r, oil, emptySet()).basis)
        }
    }

    @Test fun unknownShortHistoryNeedsInspectionAndServiceClearsAssumption() {
        val item = Planner.history(tucson.rule("engine-oil")!!, null, 500.0, monday, 40.0, null)
        val action = Planner.decide(sit.copy(items = listOf(item))) as AgentAction.Propose
        assertTrue(item.estimated && item.baselineCheck)
        assertEquals(monday, action.dueDate)
        val done = item.serviced(500.0, monday)
        assertFalse(done.estimated || done.baselineCheck)
    }

    @Test fun longHistoryStartsAtDeliveryAndKnownDateUsesDailyKm() {
        val coolant = tucson.rule("coolant")!!
        val unknown = Planner.history(coolant, null, 8000.0, monday, 40.0, 2024)
        assertFalse(unknown.replacedBefore || unknown.baselineCheck)
        assertEquals(0.0, unknown.lastServiceKm, 0.0)
        assertEquals(LocalDate.of(2024, 1, 1), unknown.lastServiceDate)
        val known = Planner.history(coolant, 1, 8000.0, monday, 40.0, 2024)
        assertEquals(6760.0, known.lastServiceKm, 0.0)
    }

    @Test fun lateArrivalThresholdsIncludeGraceAndExtendedClosingTime() {
        assertEquals(Planner.Arrival.ON_TIME, Planner.arrival(10))
        assertEquals(Planner.Arrival.OFFER_MOVE, Planner.arrival(11))
        assertEquals(Planner.Arrival.OFFER_MOVE, Planner.arrival(30))
        assertEquals(Planner.Arrival.MOVE_ONLY, Planner.arrival(31))
    }

    @Test fun cancelledShopIsExcludedFromReplacementPlan() {
        val item = oil.copy(lastServiceKm = -4900.0, cancelledShop = "demo-gangnam")
        val action = Planner.decide(sit.copy(items = listOf(item))) as AgentAction.Propose
        assertTrue(action.options.all { it.shop.id != item.cancelledShop })
        assertNull(item.booked(DemoBookingService().book(action.options.first())).cancelledShop)
    }
}
