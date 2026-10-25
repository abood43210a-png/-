package com.thakirati.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NoteStore.all(context).filter { it.reminderAt > System.currentTimeMillis() && !it.completed }
                .forEach { MainActivity.scheduleReminder(context, it) }
        }
    }
}
