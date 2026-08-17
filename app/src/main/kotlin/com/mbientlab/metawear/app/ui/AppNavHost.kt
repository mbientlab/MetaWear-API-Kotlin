package com.mbientlab.metawear.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mbientlab.metawear.app.ui.controls.ControlsScreen
import com.mbientlab.metawear.app.ui.device.DeviceDetailScreen
import com.mbientlab.metawear.app.ui.device.DeviceInfoScreen
import com.mbientlab.metawear.app.ui.firmware.FirmwareScreen
import com.mbientlab.metawear.app.ui.logging.GroupLoggingScreen
import com.mbientlab.metawear.app.ui.logging.LoggingScreen
import com.mbientlab.metawear.app.ui.scan.ScanScreen
import com.mbientlab.metawear.app.ui.sessions.SessionDetailScreen
import com.mbientlab.metawear.app.ui.sessions.SessionsScreen
import com.mbientlab.metawear.app.ui.settings.SettingsScreen
import com.mbientlab.metawear.app.ui.stream.LiveStreamScreen

/** Navigation graph: scan → device hub → feature screens (+ session history/detail). */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val back: () -> Unit = { navController.popBackStack() }

    NavHost(
        navController = navController,
        startDestination = "scan",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        composable("scan") {
            ScanScreen(
                onDeviceSelected = { navController.navigate("device") },
                onGroupLogging = { navController.navigate("group") },
                onSessionHistory = { navController.navigate("sessions") },
            )
        }
        composable("group") {
            GroupLoggingScreen(onBack = back, onSessionHistory = { navController.navigate("sessions") })
        }
        composable("device") {
            DeviceDetailScreen(
                onNavigate = { route -> navController.navigate(route) },
                onDisconnected = { navController.popBackStack("scan", inclusive = false) },
                onBack = back,
            )
        }
        composable("info") { DeviceInfoScreen(onBack = back) }
        composable("stream") { LiveStreamScreen(onBack = back) }
        composable("logging") { LoggingScreen(onBack = back) }
        composable("sessions") {
            SessionsScreen(onBack = back, onOpenSession = { id -> navController.navigate("session/$id") })
        }
        composable(
            route = "session/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            SessionDetailScreen(sessionId = entry.arguments?.getString("id").orEmpty(), onBack = back)
        }
        composable("controls") { ControlsScreen(onBack = back) }
        composable("settings") {
            SettingsScreen(
                onFactoryReset = { navController.popBackStack("device", inclusive = false) },
                onBack = back,
            )
        }
        composable("firmware") { FirmwareScreen(onBack = back) }
    }
}
