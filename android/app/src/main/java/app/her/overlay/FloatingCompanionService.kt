package app.her.overlay

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.her.HerApplication
import app.her.R
import app.her.domain.Notifier
import app.her.domain.VoiceInput
import app.her.ui.theme.HerTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the companion on screen over other apps. Turned on by the user, always labelled AI,
 * hideable from the panel or the ongoing notification, and stopped automatically if the partner
 * withdraws consent.
 */
class FloatingCompanionService : LifecycleService(), SavedStateRegistryOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var view: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val voice by lazy { VoiceInput(this) }

    // Where the user put the companion, and how far a speech bubble above it pushes the window up.
    // Kept apart so the character stays still while bubbles come and go.
    private var baseY = 0
    private var bubbleShift = 0

    override fun onCreate() {
        savedStateController.performRestore(null)
        super.onCreate()
        val container = (application as HerApplication).container
        startInForeground(container.director.presence.value.name)
        windowManager = getSystemService(WindowManager::class.java)
        val s = container.settings.settings.value
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = s.floatingX
            y = s.floatingY
        }
        baseY = s.floatingY
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingCompanionService)
            setViewTreeSavedStateRegistryOwner(this@FloatingCompanionService)
            setContent {
                HerTheme {
                    FloatingCompanionUi(
                        container = container,
                        voice = voice,
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onDragEnd = { savePosition() },
                        onBubbleHeight = { h -> setBubbleShift(h) },
                        onOpenApp = { route -> openApp(route) },
                        onHide = { hide() },
                        hasMicPermission = {
                            ContextCompat.checkSelfPermission(this@FloatingCompanionService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        },
                    )
                }
            }
        }
        windowManager.addView(composeView, params)
        view = composeView

        // Stop if the user turns it off, signs out, or the partner withdraws consent.
        lifecycleScope.launch {
            combine(container.settings.settings, container.repo.state) { st, state ->
                st.floatingEnabled && st.signedIn && state.companion?.isActive == true
            }.distinctUntilChanged().collect { allowed -> if (!allowed) stopSelf() }
        }
        lifecycleScope.launch {
            container.director.presence.map { it.name }.distinctUntilChanged().collect { startInForeground(it) }
        }
        // While on screen, re-check consent regularly so a withdrawal hides the companion promptly
        // even if the app itself isn't open.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(120_000)
                container.repo.refresh()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_HIDE) hide()
        return START_STICKY
    }

    private fun moveBy(dx: Float, dy: Float) {
        params.x = (params.x + dx).toInt()
        baseY = (baseY + dy).toInt()
        applyPosition()
    }

    private fun setBubbleShift(height: Int) {
        if (height == bubbleShift) return
        bubbleShift = height
        applyPosition()
    }

    private fun applyPosition() {
        val v = view ?: return
        params.y = baseY - bubbleShift
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    private fun savePosition() {
        val container = (application as HerApplication).container
        container.settings.update { it.copy(floatingX = params.x, floatingY = baseY) }
    }

    private fun openApp(route: String) {
        runCatching { app.her.domain.NotificationHub.sendAllowingActivityStart(this, Notifier.openAppIntent(this, route, route.hashCode())) }
    }

    private fun hide() {
        (application as HerApplication).container.settings.update { it.copy(floatingEnabled = false) }
        stopSelf()
    }

    private fun startInForeground(name: String) {
        val hide = PendingIntent.getService(
            this, 1, Intent(this, FloatingCompanionService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, Notifier.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle("$name · AI companion is on screen")
            .setContentText("Tap to open Her. Use Hide to remove it.")
            .setOngoing(true)
            .setContentIntent(Notifier.openAppIntent(this))
            .addAction(0, "Hide", hide)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        voice.stop()
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_HIDE = "app.her.overlay.HIDE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingCompanionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingCompanionService::class.java))
        }
    }
}
