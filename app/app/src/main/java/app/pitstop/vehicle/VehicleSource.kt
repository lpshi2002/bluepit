package app.pitstop.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import android.content.Context

/**
 * Vehicle SDK에서 관리에 필요한 차량 정보를 모은다.
 * - 누적 주행거리(km): 교체 시점 계산
 * - 이번 주행 거리(m)·시간(분): 짧은 주행·저속 주행 판단
 * - 차대번호: 연식
 * - 연료 잔량·배터리 충전량을 읽을 수 있는지: 엔진차인지 전기차인지
 */
class VehicleSource(context: Context, private val listener: Listener) {

    interface Listener {
        fun onOdometerKm(km: Double)
        /** 누적 주행거리를 읽지 못했다. 앱은 가상 주행거리로 돈다. */
        fun onOdometerUnavailable(e: Exception)
        fun onTripMeters(meters: Double)
        fun onTripMinutes(minutes: Int)
        fun onBrake(pressed: Boolean?)
        fun onFluid(ev: Boolean, low: Boolean)
        fun onVin(vin: String)
        fun onEnergySource(hasFuel: Boolean, hasBattery: Boolean)
        fun onError(e: Exception)
    }

    private val vehicle = Vehicle(context.applicationContext)
    private var started = false

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value?.let { listener.onOdometerKm(it.toDouble()) }
        }

        override fun onFailed(e: Exception) = listener.onError(e)
    }

    private val tripDistanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value?.let { listener.onTripMeters(it.toDouble()) }
        }

        override fun onFailed(e: Exception) = listener.onError(e)
    }

    private val tripTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) {
            value?.let(listener::onTripMinutes)
        }

        override fun onFailed(e: Exception) = listener.onError(e)
    }

    private val pedal = object : BrakePedalStatusListener {
        private var received = false
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            if (!received && status != null) { android.util.Log.i("Pitstop", "Brake pedal signal available"); received = true }
            listener.onBrake(status)
        }
        override fun onFailed(e: Exception) = listener.onError(Exception("브레이크 페달 구독", e))
    }

    private fun fluid(ev: Boolean) = object : OilLevelStateListener {
        override fun onOilLevelUpdated(level: OilLevelState?) {
            android.util.Log.i("Pitstop", "Fluid signal ev=$ev level=$level")
            if (level != null && level != OilLevelState.ERROR)
                listener.onFluid(ev, level == OilLevelState.LOW || level == OilLevelState.CRITICALLY_LOW)
        }
        override fun onFailed(e: Exception) = listener.onError(e)
    }
    private val oil = fluid(false)
    private val brakeFluid = fluid(true)

    /** SDK 연결에 성공하면 true. 실패하면 앱은 가상 주행거리로 돈다. */
    fun start(): Boolean = try {
        vehicle.initialize()
        val odometer = vehicle.getOdometer()
        odometer.getCurrentDistanceDriven(
            onComplete = { km -> km?.let { listener.onOdometerKm(it.toDouble()) } },
            onFailed = listener::onOdometerUnavailable,
        )
        odometer.registerDistanceDriven(odometerListener)
        started = true
        // 아래는 보조 정보라 하나가 실패해도 주행거리 연결은 유지한다.
        auxiliary {
            odometer.registerTripCurrentDrivingDistance(tripDistanceListener)
            odometer.registerTripCurrentDrivingTime(tripTimeListener)
        }
        auxiliary { vehicle.getCarInfo().getVINInfo(onComplete = { vin -> vin?.let(listener::onVin) }, onFailed = listener::onError) }
        auxiliary { vehicle.getBrake().registerBrakePedalStatus(pedal) }
        auxiliary { vehicle.getSafety().getEngineOilLevelState(oil::onOilLevelUpdated, listener::onError) }
        auxiliary { vehicle.getSafety().getBrakeFluidLevelState(brakeFluid::onOilLevelUpdated, listener::onError) }
        auxiliary { vehicle.getSafety().registerEngineOilLevelState(oil) }
        auxiliary { vehicle.getSafety().registerBrakeFluidLevelState(brakeFluid) }
        detectEnergySource()
        true
    } catch (e: Exception) {
        listener.onError(e)
        false
    }

    /** 두 값을 모두 물어본 뒤 한 번에 알린다. 읽기에 실패하면 그 장치가 없는 것으로 본다. */
    private fun detectEnergySource() {
        var fuel: Boolean? = null
        var battery: Boolean? = null
        val lock = Any()
        // 두 콜백이 서로 다른 스레드에서 올 수 있다.
        fun report() = synchronized(lock) {
            val f = fuel ?: return
            val b = battery ?: return
            listener.onEnergySource(f, b)
        }
        auxiliary(onFailure = { fuel = false; report() }) {
            vehicle.getFuel().getFuelPercent(onComplete = { fuel = it != null; report() }, onFailed = { fuel = false; report() })
        }
        auxiliary(onFailure = { battery = false; report() }) {
            vehicle.getEvBattery().getEvBatteryStateOfCharge(onComplete = { battery = it != null; report() }, onFailed = { battery = false; report() })
        }
    }

    /** SDK가 콜백 대신 예외를 바로 던지는 경우(권한 없음 등)도 오류로 알린다. */
    private inline fun auxiliary(onFailure: () -> Unit = {}, call: () -> Unit) {
        try {
            call()
        } catch (e: Exception) {
            listener.onError(e)
            onFailure()
        }
    }

    fun stop() {
        if (!started) return
        auxiliary { vehicle.getBrake().unregisterBrakePedalStatus(pedal) }
        auxiliary { vehicle.getSafety().unregisterEngineOilLevelState(oil) }
        auxiliary { vehicle.getSafety().unregisterBrakeFluidLevelState(brakeFluid) }
        runCatching {
            val odometer = vehicle.getOdometer()
            odometer.unregisterDistanceDriven(odometerListener)
            odometer.unregisterTripCurrentDrivingDistance(tripDistanceListener)
            odometer.unregisterTripCurrentDrivingTime(tripTimeListener)
            vehicle.release()
        }
        started = false
    }
}
