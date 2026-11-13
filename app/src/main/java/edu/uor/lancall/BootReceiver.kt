package edu.uor.lancall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the listener automatically after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        if (Store.settings.value.autoStart) CallService.start(ctx)
    }
}
