package com.mbientlab.metawear.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mbientlab.metawear.app.ui.controls.ControlsScreen
import com.mbientlab.metawear.app.ui.device.DeviceDetailScreen
import com.mbientlab.metawear.app.ui.device.DeviceInfoScreen
import com.mbientlab.metawear.app.ui.firmware.FirmwareScreen
import com.mbientlab.metawear.app.ui.logging.GroupLoggingScreen
import com.mbientlab.metawear.app.ui.logging.LoggingScreen
import com.mbientlab.metawear.app.ui.scan.ScanScreen
import com.mbientlab.metawear.app.ui.sessions.SessionsScreen
import com.mbientlab.metawear.app.ui.settings.SettingsScreen
import com.mbientlab.metawear.app.ui.stream.LiveStreamScreen

/** Navigation graph: scan → device hub → feature screens. */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()

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
            )
        }
        composable("group") { GroupLoggingScreen() }
        composable("device") {
            DeviceDetailScreen(
                onNavigate = { route -> navController.navigate(route) },
                onDisconnected = { navController.popBackStack("scan", inclusive = false) },
            )
        }
        composable("info") { DeviceInfoScreen() }
        composable("stream") { LiveStreamScreen() }
        composable("logging") { LoggingScreen() }
        composable("sessions") { SessionsScreen() }
        composable("controls") { ControlsScreen() }
        composable("settings") {
            SettingsScreen(onFactoryReset = { navController.popBackStack("device", inclusive = false) })
        }
        composable("firmware") { FirmwareScreen() }
    }
}
