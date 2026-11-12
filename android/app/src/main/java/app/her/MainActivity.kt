package app.her

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.her.domain.Notifier
import app.her.ui.HerApp
import app.her.ui.theme.HerTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val routeEvents = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        routeEvents.value = intent?.getStringExtra(Notifier.EXTRA_ROUTE)
        val container = (application as HerApplication).container
        setContent {
            HerTheme {
                HerApp(container, routeEvents)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { routeEvents.value = it }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as HerApplication).container
        container.repo.refreshAsync()
        container.weather.refreshIfStale()
    }
}
