package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.AetherApplication
import com.example.engine.DiagnosticsCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reconciles `AETHLDG2` binary ledgers against Room on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`
 * so any interrupted `STATE_LEASED` segments cleanly roll back to `STATE_PENDING` -> `PAUSED`.
 */
class BootLedgerReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val app = context.applicationContext as? AetherApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DiagnosticsCenter.log("BOOT_RECONCILE", "Triggered on $action")
                app.downloadEngine.reconcileLedgersOnStartup()
            } finally {
                pending.finish()
            }
        }
    }
}
