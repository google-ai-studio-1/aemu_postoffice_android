package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PostOfficeStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == PostOfficeForegroundService.ACTION_STOP) {
            PostOfficeForegroundService.stopServer(context.applicationContext)
        }
    }
}
