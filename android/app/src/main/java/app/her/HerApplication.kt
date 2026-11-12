package app.her

import android.app.Application
import app.her.data.HerRepository
import app.her.data.WeatherRepository
import app.her.data.local.HerDatabase
import app.her.data.remote.HerApi
import app.her.data.settings.SettingsStore
import app.her.domain.ChatEngine
import app.her.domain.CompanionDirector
import app.her.domain.Notifier
import app.her.domain.SpeechController
import app.her.work.CompanionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependencies; one instance per process. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app, scope)
    val api = HerApi(settings)
    val db = HerDatabase.create(app)
    val repo = HerRepository(app, api, settings, scope)
    val weather = WeatherRepository(settings, scope)
    val director = CompanionDirector(repo, settings, weather, db, scope)
    val speech = SpeechController(app, repo, settings, scope)
    val chat = ChatEngine(repo, director, settings, speech, scope)
}

class HerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        container.weather.start()
        CompanionWorker.schedule(this)
        container.repo.refreshAsync()
    }
}
