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
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.DrivingInfo
import android.os.Handler
import android.os.Looper

/** NaviHelper로 집·회사 즐겨찾기를 읽고, 정비소를 목적지나 경유지로 넣는다. */
class NaviClient(
    context: Context,
    private val onTravelTime: (Int) -> Unit,
    private val onNaviError: (String) -> Unit,
    private val onGuidance: (Boolean?, Double?) -> Unit,
    private val onHazard: (Boolean) -> Unit,
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

    private var guided = false
    private var nearTurn = false
    private var nearCamera = false
    private var nearCaution = false
    private var nearDownhill = emptySet<String>()
    private var routeMeters: Int? = null

    override fun onNavigationStatus(status: NavigationStatus) {
        main.post {
            guided = status == NavigationStatus.ROUTE_DRIVE
            if (!guided) {
                nearDownhill = emptySet()
                routeMeters = null
                nearTurn = false
                nearCamera = false
                nearCaution = false
                onHazard(false)
            }
        }
    }

    override fun onDrivingInfo(drivingInfo: DrivingInfo) {
        main.post { routeMeters = drivingInfo.destination?.distance }
    }

    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
        main.post {
            if (routeStateInfo.isRouting == true) guided = true
            val distance = routeMeters ?: return@post
            val congestion = routeStateInfo.trafficStatus?.summary?.totalCongestionDist ?: return@post
            if (guided && distance > 0) onGuidance(null, congestion / distance)
        }
    }

    override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
        main.post {
            if (!guided) return@post
            val downhill = cautionPointList.filter { it.type == CautionPointType.DOWNHILL_CAUTION_AREA }
            val near = downhill.filter { it.remainDistance in 0..30 }.mapNotNull { it.id }.toSet()
            // An alert that was within 30 m and then left the current guidance list was passed.
            val present = downhill.mapNotNull { it.id }.toSet()
            onGuidance((nearDownhill - present).isNotEmpty() || downhill.any { it.remainDistance == 0 }, null)
            nearDownhill = near
            nearCaution = cautionPointList.any { it.remainDistance in 0..300 }
            onHazard(nearTurn || nearCamera || nearCaution)
        }
    }

    override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
        main.post {
            nearTurn = guided && tbtInfoList.any { (it.distance ?: -1.0) in 0.0..300.0 }
            onHazard(nearTurn || nearCamera || nearCaution)
        }
    }

    override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
        main.post {
            nearCamera = guided && cameraAlertList.any { it.remainDistance in 0..300 }
            onHazard(nearTurn || nearCamera || nearCaution)
        }
    }

    /** 연결에 성공하면 true. 즐겨찾기는 onBookmarkInfo로 나중에 들어온다. */
    fun start(): Boolean = try {
        helper.initialize()
        helper.addListener(this)
        helper.getBookmarkInfo()
        helper.getRouteStateInfo()
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
