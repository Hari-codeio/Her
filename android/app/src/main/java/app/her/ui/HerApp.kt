package app.her.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.her.AppContainer
import app.her.data.remote.ApiException
import app.her.overlay.FloatingCompanionService
import app.her.ui.chat.ChatScreen
import app.her.ui.games.GamesScreen
import app.her.ui.home.HomeScreen
import app.her.ui.likeness.LikenessScreen
import app.her.ui.memories.MemoriesScreen
import app.her.ui.memories.OccasionsScreen
import app.her.ui.more.MoreScreen
import app.her.ui.onboarding.OnboardingScreen
import app.her.ui.onboarding.PairScreen
import app.her.ui.settings.NotificationsScreen
import app.her.ui.settings.PrivacyScreen
import app.her.ui.settings.SettingsScreen
import app.her.ui.studio.StudioScreen
import app.her.ui.wardrobe.WardrobeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalRunner = staticCompositionLocalOf<Runner> { error("Runner not provided") }

/** Runs an action on the app scope (survives navigation) and reports the outcome in a snackbar. */
class Runner(private val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    fun run(done: String? = null, onError: (String) -> Unit = {}, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                onDone()
                done?.let { snackbar.showSnackbar(it) }
            } catch (e: ApiException) {
                val message = describeApiError(e.code)
                onError(message)
                snackbar.showSnackbar(message)
            } catch (e: Exception) {
                val message = e.message ?: "Something went wrong"
                onError(message)
                snackbar.showSnackbar(message)
            }
        }
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
}

fun describeApiError(code: String): String = when (code) {
    "network" -> "Can't reach the server. Check the connection or server address."
    "invalid_code" -> "That code didn't work. It may have expired or been used."
    "already_paired" -> "You're already paired."
    "own_code" -> "That's your own code — share it with your partner instead."
    "likeness_consent_required" -> "Your partner needs to give consent first."
    "voice_consent_required" -> "Voice consent is needed for this."
    "likeness_provider_not_configured" -> "Realistic rendering isn't connected yet, so the stylised character is used."
    "voice_provider_not_configured" -> "Voice cloning isn't connected yet, so the phone's voice is used."
    "stale_statement" -> "The consent wording was updated. Please review it again."
    "forbidden" -> "Only your partner can do that."
    "companion_not_active" -> "The companion isn't active yet."
    "unauthorized" -> "Your session expired. Please sign in again."
    "chat_unavailable" -> "The AI service isn't available right now."
    "rate_limited" -> "Slow down a little and try again in a minute."
    "invalid_input" -> "Some details aren't valid. Please check and try again."
    else -> "Something went wrong ($code)."
}

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val WARDROBE = "wardrobe"
    const val MEMORIES = "memories"
    const val MORE = "more"
    const val OCCASIONS = "occasions"
    const val GAMES = "games"
    const val STUDIO = "studio"
    const val LIKENESS = "likeness"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
}

@Composable
fun HerApp(container: AppContainer, routeEvents: MutableStateFlow<String?>) {
    val snackbar = remember { SnackbarHostState() }
    val runner = remember { Runner(container.scope, snackbar) }
    CompositionLocalProvider(LocalContainer provides container, LocalRunner provides runner) {
        val settings by container.settings.settings.collectAsStateWithLifecycle()
        val state by container.repo.state.collectAsStateWithLifecycle()
        when {
            !settings.signedIn -> OnboardingScreen(snackbar)
            !state.paired -> PairScreen(snackbar)
            else -> MainScaffold(container, snackbar, routeEvents)
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, snackbar: SnackbarHostState, routeEvents: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val pendingRoute by routeEvents.collectAsStateWithLifecycle()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            nav.navigate(route) { launchSingleTop = true }
            routeEvents.value = null
        }
    }
    LaunchedEffect(settings.floatingEnabled, state.companion?.status) {
        if (settings.floatingEnabled && state.companion?.isActive == true && canDrawOverlays(context)) {
            FloatingCompanionService.start(context)
        } else if (!settings.floatingEnabled) {
            FloatingCompanionService.stop(context)
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route?.substringBefore('?')
    val tabs = listOf(
        Triple(Routes.HOME, "Home", Icons.Filled.Home),
        Triple(Routes.CHAT, "Chat", Icons.Filled.ChatBubble),
        Triple(Routes.WARDROBE, "Wardrobe", Icons.Filled.Checkroom),
        Triple(Routes.MEMORIES, "Memories", Icons.Filled.Favorite),
        Triple(Routes.MORE, "More", Icons.Filled.MoreHoriz),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current in tabs.map { it.first }) {
                NavigationBar {
                    tabs.forEach { (route, label, icon) ->
                        NavigationBarItem(
                            selected = current == route,
                            onClick = { nav.navigateTab(route) },
                            icon = {
                                if (route == Routes.MORE && state.pendingForMe > 0) {
                                    BadgedBox(badge = { Badge { Text("${state.pendingForMe}") } }) { Icon(icon, contentDescription = label) }
                                } else {
                                    Icon(icon, contentDescription = label)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(onNavigate = { nav.navigate(it) }) }
            composable(
                "${Routes.CHAT}?game={game}",
                arguments = listOf(navArgument("game") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry -> ChatScreen(game = entry.arguments?.getString("game"), onNavigate = { nav.navigate(it) }) }
            composable(Routes.WARDROBE) { WardrobeScreen() }
            composable(Routes.MEMORIES) { MemoriesScreen() }
            composable(Routes.MORE) { MoreScreen(onNavigate = { nav.navigate(it) }) }
            composable(Routes.OCCASIONS) { OccasionsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.GAMES) { GamesScreen(onBack = { nav.popBackStack() }, onPlay = { game -> nav.navigate("${Routes.CHAT}?game=$game") }) }
            composable(Routes.STUDIO) { StudioScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LIKENESS) { LikenessScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
            composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

fun overlayPermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

fun notificationListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

fun notificationListenerIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
