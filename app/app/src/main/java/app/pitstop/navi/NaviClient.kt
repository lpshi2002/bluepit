package app.pitstop.navi

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import app.pitstop.agent.Place
import app.pitstop.agent.PlaceKind
import app.pitstop.agent.Shop
import android.content.Context
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointInfo
import android.os.Handler
import android.os.Looper

/** NaviHelper로 집·회사 즐겨찾기를 읽고, 정비소를 목적지나 경유지로 넣는다. */
class NaviClient(
    context: Context,
    private val onTravelTime: (Int) -> Unit,
    private val onNaviError: (String) -> Unit,
    private val onPlaces: (List<Place>) -> Unit,
) : NaviHelperEventListener {

    private val helper = NaviHelper(context.applicationContext)
    private var started = false

    private val main = Handler(Looper.getMainLooper())
    private var removal: Pair<Shop, (String) -> Unit>? = null

    /** 현재 첫 경유지가 확정한 샘플 정비소와 일치할 때만 삭제를 요청한다. */
    fun removeMatchingWaypoint(shop: Shop, report: (String) -> Unit) {
        val request = shop to report
        removal = request
        runCatching { helper.getWaypointInfo() }.onFailure {
            removal = null
            report("내비에서 경유지를 직접 확인해 주세요.")
        }
        main.postDelayed({
            if (removal === request) {
                removal = null
                report("내비의 경유지 응답이 없어 직접 확인이 필요해요.")
            }
        }, 3000)
    }

    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
        main.post {
            val (shop, report) = removal ?: return@post
            removal = null
            val first = waypointInfoList.firstOrNull()
            if (first?.name == shop.name && first.latitude == shop.lat && first.longitude == shop.lon) {
                val result = runCatching { helper.removeWaypoint(WaypointIndex.FIRST) }
                report(if (result.isSuccess) "해당 정비소 경유지 삭제를 요청했어요." else "내비에서 경유지를 직접 확인해 주세요.")
            } else report("첫 경유지에서 해당 정비소를 확인할 수 없어요.")
        }
    }

    /** 연결에 성공하면 true. 즐겨찾기는 onBookmarkInfo로 나중에 들어온다. */
    fun start(): Boolean = try {
        helper.initialize()
        helper.addListener(this)
        helper.getBookmarkInfo()
        started = true
        true
    } catch (e: Exception) {
        false
    }

    fun stop() {
        removal = null
        main.removeCallbacksAndMessages(null)
        if (!started) return
        runCatching {
            helper.removeListener(this)
            helper.release()
        }
        started = false
    }

    /** 이미 경로 안내 중일 때 쓴다. 정비소를 첫 경유지로 넣는다. */
    fun addWaypoint(shop: Shop): Result<Unit> = runCatching {
        helper.addWaypoint(
            RequestWaypointInfo(
                latitude = shop.lat,
                longitude = shop.lon,
                waypointIndex = WaypointIndex.FIRST,
                poiId = "",
                poiName = shop.name,
                poiSubId = "0",
                address = shop.address,
            )
        )
    }

    fun requestTravelTime(shop: Shop) = runCatching {
        helper.requestTrip(TripRequest(shop.lat, shop.lon, "", shop.name, "0"))
    }

    override fun onTripInfo(tripInfo: TripInfo) {
        tripInfo.eta?.takeIf { it >= 0 }?.let(onTravelTime)
    }

    override fun onError(errorCode: NaviErrorCode) = onNaviError(errorCode.name)

    fun routeTo(shop: Shop): Result<Unit> = runCatching {
        helper.requestRoute(
            RouteInfo(
                latitude = shop.lat,
                longitude = shop.lon,
                poiId = "",
                poiName = shop.name,
                poiSubId = "0",
                address = shop.address,
            )
        )
    }

    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
        onPlaces(bookmarkInfoList.mapNotNull(::toPlace))
    }

    private fun toPlace(bookmark: BookmarkInfo): Place? {
        val kind = when (bookmark.category) {
            BookmarkType.HOME -> PlaceKind.HOME
            BookmarkType.OFFICE -> PlaceKind.OFFICE
            else -> return null
        }
        val lat = bookmark.latitude ?: return null
        val lon = bookmark.longitude ?: return null
        // 집·회사를 등록하지 않으면 내비가 좌표 (0, 0)과 빈 이름으로 준다 (에뮬레이터에서 확인).
        if (lat == 0.0 && lon == 0.0) return null
        val name = bookmark.name?.takeIf { it.isNotBlank() } ?: if (kind == PlaceKind.HOME) "집" else "회사"
        return Place(kind, name, lat, lon)
    }

}
