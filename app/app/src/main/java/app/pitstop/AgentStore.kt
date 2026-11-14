package app.pitstop

import app.pitstop.agent.Booking
import app.pitstop.agent.MaintenanceItem
import app.pitstop.agent.OdometerSample
import app.pitstop.agent.SevereCondition
import app.pitstop.agent.Trip
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 앱 상태를 SharedPreferences에 JSON으로 저장한다. 데이터가 몇 KB라 DB는 쓰지 않는다. */
class AgentStore(context: Context, private val key: String = "state-v2") {

    data class Saved(
        /** 사용자가 고른 차종. null이면 차량 SDK로 감지한 동력계의 기본 차종을 쓴다. */
        val specId: String? = null,
        val items: List<MaintenanceItem> = emptyList(),
        val samples: List<OdometerSample> = emptyList(),
        val trips: List<Trip> = emptyList(),
        /** 주행 기록으로 알 수 없어 사용자가 직접 알려 준 가혹 조건. */
        val declared: Set<SevereCondition> = emptySet(),
        /** 시연용 시계를 오늘보다 며칠 앞당겼는지. */
        val demoDayOffset: Long = 0,
        /** 시연용으로 실제 주행거리에 더한 km. */
        val demoExtraKm: Double = 0.0,
        val setupDone: Boolean = false,
        val demo: Boolean = false,
        val partnerDemo: Boolean = false,
        val demoLow: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("pitstop", Context.MODE_PRIVATE)

    fun load(): Saved {
        val json = prefs.getString(key, null)?.let(::JSONObject) ?: return Saved()
        return Saved(
            setupDone = json.optBoolean("setupDone", json.getJSONArray("items").length() > 0),
            demo = json.optBoolean("demo", !json.has("setupDone")),
            partnerDemo = json.optBoolean("partnerDemo"),
            demoLow = json.optBoolean("demoLow"),
            specId = json.optString("specId").takeIf { it.isNotEmpty() },
            items = json.getJSONArray("items").objects().map(::item),
            samples = json.getJSONArray("samples").objects().map {
                OdometerSample(LocalDate.parse(it.getString("date")), it.getDouble("km"))
            },
            trips = json.getJSONArray("trips").objects().map {
                Trip(LocalDate.parse(it.getString("date")), it.getDouble("km"), it.getInt("minutes"),
                    it.optDouble("brakes").takeIf(Double::isFinite), it.optDouble("congestion").takeIf(Double::isFinite))
            },
            declared = json.getJSONArray("declared").let { array ->
                (0 until array.length()).mapNotNull { i -> SevereCondition.entries.firstOrNull { it.name == array.getString(i) } }.toSet()
            },
            demoDayOffset = json.optLong("demoDayOffset"),
            demoExtraKm = json.optDouble("demoExtraKm", 0.0),
        )
    }

    fun save(saved: Saved) {
        val json = JSONObject()
            .put("specId", saved.specId)
            .put("setupDone", saved.setupDone).put("demo", saved.demo)
            .put("partnerDemo", saved.partnerDemo).put("demoLow", saved.demoLow)
            .put("items", JSONArray(saved.items.map(::json)))
            .put("samples", JSONArray(saved.samples.map { JSONObject().put("date", it.date.toString()).put("km", it.km) }))
            .put("trips", JSONArray(saved.trips.map {
                JSONObject().put("date", it.date.toString()).put("km", it.km).put("minutes", it.minutes)
                    .put("brakes", it.brakesPerMinute).put("congestion", it.congestion)
            }))
            .put("declared", JSONArray(saved.declared.map { it.name }))
            .put("demoDayOffset", saved.demoDayOffset)
            .put("demoExtraKm", saved.demoExtraKm)
        prefs.edit().putString(key, json.toString()).apply()
    }

    private fun json(item: MaintenanceItem) = JSONObject()
        .put("ruleId", item.ruleId)
        .put("cancelledShop", item.cancelledShop)
        .put("estimated", item.estimated).put("baselineCheck", item.baselineCheck)
        .put("lastServiceKm", item.lastServiceKm)
        .put("lastServiceDate", item.lastServiceDate.toString())
        .put("replacedBefore", item.replacedBefore)
        .put("snoozedUntil", item.snoozedUntil?.toString())
        .put("booking", item.booking?.let {
            JSONObject()
                .put("shopId", it.shopId)
                .put("date", it.date.toString())
                .put("hour", it.hour).put("minute", it.minute)
                .put("confirmation", it.confirmation)
        })

    private fun item(json: JSONObject) = MaintenanceItem(
        cancelledShop = json.optString("cancelledShop").takeIf { it.isNotEmpty() },
        estimated = json.optBoolean("estimated"), baselineCheck = json.optBoolean("baselineCheck"),
        ruleId = json.getString("ruleId"),
        lastServiceKm = json.getDouble("lastServiceKm"),
        lastServiceDate = LocalDate.parse(json.getString("lastServiceDate")),
        replacedBefore = json.optBoolean("replacedBefore", true),
        snoozedUntil = json.optString("snoozedUntil").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
        booking = json.optJSONObject("booking")?.let {
            Booking(it.getString("shopId"), LocalDate.parse(it.getString("date")), it.getInt("hour"), it.getString("confirmation"), it.optInt("minute"))
        },
    )

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)

}
