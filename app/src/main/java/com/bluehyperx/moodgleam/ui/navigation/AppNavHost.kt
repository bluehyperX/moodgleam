package com.bluehyperx.moodgleam.ui.navigation

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.bluehyperx.moodgleam.HelpDialog
import com.bluehyperx.moodgleam.MainScreen
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.UrlDialog
import com.bluehyperx.moodgleam.common.util.Preferences
import com.bluehyperx.moodgleam.ui.camera.CameraSetupScreen
import com.bluehyperx.moodgleam.ui.led.LedLayoutScreen
import com.bluehyperx.moodgleam.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    isRunning: Boolean,
    connectedDeviceName: String? = null,
    onToggleClick: () -> Unit,
    onEffectsClick: () -> Unit,
    onEffectsLongClick: () -> Unit = {},
    onReportIssueClick: () -> Unit = {},
    effectMode: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            val context = LocalContext.current
            var showHelpDialog by remember { mutableStateOf(false) }
            var showUrlDialog by remember { mutableStateOf<String?>(null) }

            val isTv = remember {
                val uiModeManager =
                    context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            }

            // Read capture source from preferences (re-reads when returning from settings)
            val captureSource = remember(navController.currentBackStackEntry) {
                Preferences(context).getString(R.string.pref_key_capture_source, "screen")
                    ?: "screen"
            }

            MainScreen(
                isRunning = isRunning,
                onToggleClick = onToggleClick,
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onEffectsClick = onEffectsClick,
                onEffectsLongClick = onEffectsLongClick,
                effectMode = effectMode,
                captureSource = captureSource,
                connectedDeviceName = connectedDeviceName,
                onHelpClick = {
                    showHelpDialog = true
                },
                onReportIssueClick = onReportIssueClick
            )

            if (showHelpDialog) {
                HelpDialog(
                    onDismiss = { showHelpDialog = false },
                    onOpenGitHub = {
                        val url = context.getString(R.string.help_readme_url)
                        showHelpDialog = false

                        if (isTv) {
                            showUrlDialog = url
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                showUrlDialog = url
                            } catch (e: Exception) {
                                showUrlDialog = url
                            }
                        }
                    }
                )
            }

            val urlToShow = showUrlDialog
            if (urlToShow != null && !showHelpDialog) {
                UrlDialog(
                    url = urlToShow,
                    onDismiss = {
                        showUrlDialog = null
                    },
                    onOpenLink = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToShow)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            showUrlDialog = null
                        } catch (e: Exception) {
                            Log.e("AppNavHost", "Failed to open link", e)
                        }
                    }
                )
            }

        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLedLayoutClick = { navController.navigate(Screen.LedLayout.route) },
                onCameraSetupClick = { navController.navigate(Screen.CameraSetup.route) }
            )
        }
        composable(Screen.LedLayout.route) {
            LedLayoutScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Screen.CameraSetup.route) {
            CameraSetupScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
