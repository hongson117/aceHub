package vn.lienson.acesport.g2probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot broadcast received: $action")

        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_REBOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        if (action in validActions) {
            // 1. Always launch AceHubControlService on boot / app update for 24/7 remote management
            try {
                Log.i(TAG, "Launching AceHubControlService in foreground...")
                val controlIntent = Intent(context, vn.lienson.acesport.g2probe.control.AceHubControlService::class.java)
                ContextCompat.startForegroundService(context, controlIntent)
                Log.i(TAG, "AceHubControlService startForegroundService invoked successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AceHubControlService on boot: ${e.message}", e)
            }

            // 2. Launch G2OrchestratorService if auto-start is enabled
            val config = G2ConfigManager(context)
            if (config.isAutoStartBoot) {
                Log.i(TAG, "Auto-start enabled. Launching G2OrchestratorService in foreground...")
                try {
                    val serviceIntent = Intent(context, G2OrchestratorService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.i(TAG, "G2OrchestratorService startForegroundService invoked successfully.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start G2OrchestratorService on boot: ${e.message}", e)
                }
            } else {
                Log.i(TAG, "Auto-start on boot is disabled by user configuration.")
            }
        }
    }
}
