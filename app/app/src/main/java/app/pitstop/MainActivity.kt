package app.pitstop

import app.pitstop.ui.AgentScreen
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.VehiclePropertyIds
import android.car.VehicleIgnitionState
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    private var car: Car? = null
    private var restrictions: CarUxRestrictionsManager? = null

    private var properties: CarPropertyManager? = null
    private var ignitionOn = false
    private val ignition = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(event: CarPropertyValue<*>) {
            if (event.status != CarPropertyValue.STATUS_AVAILABLE) return
            val on = event.value == VehicleIgnitionState.ON || event.value == VehicleIgnitionState.START
            if (on && !ignitionOn) viewModel.onIgnition()
            ignitionOn = on
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    override fun onStart() {
        super.onStart()
        viewModel.foreground(true)
        viewModel.allowInteraction(false)
        runCatching {
            car = Car.createCar(this)
            restrictions = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            restrictions?.registerListener { viewModel.allowInteraction(!it.isRequiresDistractionOptimization) }
            viewModel.allowInteraction(restrictions?.currentCarUxRestrictions?.isRequiresDistractionOptimization == false)
        }.onFailure { viewModel.allowInteraction(false) }
        runCatching {
            properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            properties?.registerCallback(ignition, VehiclePropertyIds.IGNITION_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }
    }

    override fun onStop() {
        properties?.unregisterCallback(ignition)
        properties = null
        ignitionOn = false
        viewModel.foreground(false)
        restrictions?.unregisterListener()
        car?.disconnect()
        restrictions = null
        car = null
        viewModel.allowInteraction(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AgentScreen(viewModel)
            }
        }
    }
}
