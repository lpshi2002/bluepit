package app.pitstop.agent

import app.pitstop.voice.VoiceCommand
import app.pitstop.voice.voiceCommand
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ProposalTest {
    private val today = LocalDate.of(2026, 11, 14)
    private val ev = ScheduleCatalog.spec("ne1")!!
    private fun profile(trips: List<Trip>) = DrivingAnalyzer.analyze(trips, today, 32_000.0, null)

    @Test fun mountainNeedsTenGuidedTrips() {
        val hill = Trip(today, 60.0, 90, downhill = true)
        assertNull(profile(List(9) { hill } + hill.copy(downhill = null)).scores[SevereCondition.MOUNTAIN])
        val conditions = profile(List(10) { hill }).detected
        assertTrue(SevereCondition.MOUNTAIN in conditions)
        val oil = ev.rule("reduction-gear-oil-replace")!!
        assertEquals(120000, Planner.applied(oil, MaintenanceItem(oil.id, 0.0, today), conditions).interval.km)
    }

    @Test fun guidedObservationsStayWithinTheTrip() {
        val t = TripTracker()
        t.onMeters(10000.0, today)
        t.onMinutes(20)
        t.onGuidance(false, .7)
        t.onGuidance(true, null)
        t.onGuidance(false, null)
        val first = t.onMeters(0.0, today)!!
        assertEquals(true, first.downhill)
        assertEquals(.7, first.congestion!!, 0.0)
        t.onMeters(5000.0, today)
        assertNull(t.onMeters(0.0, today)!!.downhill)
    }

    @Test fun fluidWarningIncludesUpcomingTireWork() {
        val action = Planner.decide(Situation(today, 32000.0, emptyList(), ev,
            DemoData.initialItems(ev, 32000.0, today), emptySet(), DemoData.fallbackPlaces,
            DemoData.shops, DemoData.availability, lowFluid = true)) as AgentAction.Propose
        assertEquals("brake-fluid-level", action.rule.id)
        assertTrue(action.together.any { it.id == "tire-rotation" })
    }

    @Test fun noApprovalForNegativeOrAmbiguousSpeech() {
        assertEquals(VoiceCommand.ACCEPT, voiceCommand("응, 예약해줘"))
        assertEquals(VoiceCommand.CONFIRM, voiceCommand("예약 확정."))
        assertEquals(VoiceCommand.CONFIRM_CANCEL, voiceCommand("취소 확정"))
        for (phrase in listOf("확정하지 마", "화장", "예약할지 모르겠어", "")) {
            assertEquals(VoiceCommand.UNKNOWN, voiceCommand(phrase))
        }
    }

    @Test fun directoriesRespectOpeningHours() {
        val place = DemoData.fallbackPlaces.first { it.kind == PlaceKind.OFFICE }
        val shop = Shop("public", "정비소", "도로명", place.lat, place.lon, opening = 540, closing = 1080)
        val situation = Situation(today, 32000.0, emptyList(), ev, emptyList(), emptySet(),
            listOf(place), listOf(shop), DemoData.availability)
        val options = Planner.options(today.plusDays(9), situation)
        assertTrue(options.isNotEmpty())
        assertTrue(options.all { it.hour == 12 })
    }
}
