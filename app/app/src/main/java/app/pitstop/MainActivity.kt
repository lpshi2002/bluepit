package app.pitstop

import app.pitstop.ui.AgentScreen
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
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

    override fun onStart() {
        super.onStart()
        viewModel.allowInteraction(false)
        runCatching {
            car = Car.createCar(this)
            restrictions = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            restrictions?.registerListener { viewModel.allowInteraction(!it.isRequiresDistractionOptimization) }
            viewModel.allowInteraction(restrictions?.currentCarUxRestrictions?.isRequiresDistractionOptimization == false)
        }.onFailure { viewModel.allowInteraction(false) }
    }

    override fun onStop() {
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
