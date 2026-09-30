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
