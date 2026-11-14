package app.pitstop.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DrivingPatternTest {
    private val today = LocalDate.of(2026, 11, 2)

    private fun trips(count: Int, km: Double, minutes: Int) = List(count) { Trip(today.minusDays(it % 20L), km, minutes) }

    @Test
    fun `주행이 10회보다 적으면 판단하지 않는다`() {
        val profile = DrivingAnalyzer.analyze(trips(9, 3.0, 15), today, 30_000.0, 2024)
        assertNull(profile.shortTripShare)
        assertTrue(profile.detected.isEmpty())
    }

    @Test
    fun `8km 미만 주행이 절반 이상이면 짧은 거리 반복 주행이다`() {
        val profile = DrivingAnalyzer.analyze(trips(6, 5.0, 10) + trips(4, 20.0, 30), today, 30_000.0, 2024)
        assertEquals(0.6, profile.shortTripShare!!, 0.001)
        assertTrue(SevereCondition.SHORT_TRIPS in profile.detected)
    }

    @Test
    fun `평균 20kmh 미만 주행이 절반 이상이면 교통 체증 구역 주행이다`() {
        // 15 km를 60분에 달리면 15 km/h
        val profile = DrivingAnalyzer.analyze(trips(10, 15.0, 60), today, 30_000.0, 2024)
        assertTrue(SevereCondition.CONGESTION in profile.detected)
        assertTrue(SevereCondition.SHORT_TRIPS !in profile.detected)
    }

    @Test
    fun `30일보다 오래된 주행은 빼고 본다`() {
        val old = List(10) { Trip(today.minusDays(40), 3.0, 10) }
        assertEquals(0, DrivingAnalyzer.analyze(old, today, 30_000.0, 2024).tripCount)
    }

    @Test
    fun `10년이 넘었거나 15만km를 넘으면 오래된 차량 조건이다`() {
        assertTrue(SevereCondition.OLD_VEHICLE in DrivingAnalyzer.analyze(emptyList(), today, 30_000.0, 2016).detected)
        assertTrue(SevereCondition.OLD_VEHICLE in DrivingAnalyzer.analyze(emptyList(), today, 150_000.0, 2024).detected)
        assertTrue(DrivingAnalyzer.analyze(emptyList(), today, 30_000.0, 2017).detected.isEmpty())
    }

    @Test
    fun `이번 주행 거리가 0으로 돌아가면 직전 주행을 하나로 기록한다`() {
        val tracker = TripTracker()
        tracker.onMinutes(5); assertNull(tracker.onMeters(2_000.0, today))
        tracker.onMinutes(12); assertNull(tracker.onMeters(6_500.0, today))
        // 시간이 먼저 0으로 돌아가도 직전 주행 시간(12분)을 쓴다.
        tracker.onMinutes(0)
        assertEquals(Trip(today, 6.5, 12), tracker.onMeters(0.0, today))
        // 거리가 먼저 돌아가는 경우
        tracker.onMinutes(20); tracker.onMeters(15_000.0, today)
        assertEquals(Trip(today, 15.0, 20), tracker.onMeters(10.0, today))
        tracker.onMinutes(0)
        tracker.onMinutes(3); tracker.onMeters(1_000.0, today)
        tracker.onMinutes(0)
        assertEquals(Trip(today, 1.0, 3), tracker.onMeters(0.0, today))
    }

    @Test
    fun `차대번호 10번째 자리로 연식을 읽는다`() {
        assertEquals(2024, modelYearFromVin("KMHxxxxxxRxxxxxxx", today))
        assertEquals(2021, modelYearFromVin("KMHxxxxxxMxxxxxxx", today))
        assertEquals(2027, modelYearFromVin("KMHxxxxxxVxxxxxxx", today))
        assertNull(modelYearFromVin("TOO_SHORT", today))
        assertNull(modelYearFromVin("KMHxxxxxxQxxxxxxx", today))
    }
}
