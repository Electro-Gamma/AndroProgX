/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that holds AndroProgX's process at foreground priority while the CH341A is
 * connected. Without this, once [MainActivity] is backgrounded (Home, screen off, switch app) and
 * nothing else keeps the process important, Android is free to kill it under memory pressure -
 * silently dropping the USB connection, aborting whatever Read/Write/Erase/Verify/Dump was
 * in-flight, closing every I2C-over-TCP client, and cutting off the OLED clock/stats/animation
 * loop. A running foreground service raises the whole process to the same priority tier as a
 * visible activity, which is what actually prevents that - this service doesn't need to own the
 * USB connection or any coroutine itself to do that job.
 *
 * All the real work - [ProgrammerController], its `lifecycleScope` launches, the I2C-over-TCP
 * server - is untouched and still lives exactly where it did before; this class only starts/stops
 * alongside that work (see [start]/[stop], called from [MainActivity]'s `connectToProgrammerDevice`
 * / `onProgConnectClicked` / `onDestroy`) and keeps a persistent, low-priority notification
 * (icon: [R.drawable.ic_notification]) showing what's currently connected/running, updated via a
 * fresh [start] call (e.g. from `onI2cTcpToggleClicked`) rather than a separate method - Android
 * already treats a repeat `startForegroundService` call on a running service as "update the
 * notification", so a second method would just be two ways to do the same thing.
 *
 * Registered in AndroidManifest.xml with `foregroundServiceType="connectedDevice"`, matching the
 * `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission declared there - the correct type for an app
 * whose whole point is talking to a USB-attached device. Notifications need the runtime
 * `POST_NOTIFICATIONS` permission on Android 13+ (see MainActivity's `ensureNotificationPermission`)
 * - if that's denied the service still starts and still protects the process, it just can't show
 * anything while it does.
 */
class ProgrammerService : Service() {

    // Not a bound service - MainActivity only ever starts/stops it (see companion) and otherwise
    // talks to the same ProgrammerController instance it already had, so there's nothing to bind to.
    override fun onBind(intent: Intent?): IBinder? = null

    /** Survives a null intent (e.g. the system restarting this service after killing it under
     *  extreme memory pressure, per [START_STICKY]) by falling back to whatever status was shown
     *  last, so the notification never regresses to a blank/placeholder text. */
    private var currentStatus: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_STATUS)?.let { currentStatus = it }
        startForeground(NOTIFICATION_ID, buildNotification(currentStatus))
        return START_STICKY
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(this, 0, openAppIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.prog_service_title))
            .setContentText(status)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.prog_service_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.prog_service_channel_desc)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "programmer_connection"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_STATUS = "status"

        /**
         * Starts the foreground service showing [status], or - if it's already running - just
         * updates the notification text to [status]. Call right after [ProgrammerController.connect]
         * succeeds, and again whenever what's worth telling the user changes (I2C-over-TCP server
         * started/stopped, connected mode changed).
         */
        fun start(context: Context, status: String) {
            val intent = Intent(context, ProgrammerService::class.java).putExtra(EXTRA_STATUS, status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the foreground service - call right after [ProgrammerController.disconnect], and
         *  from [MainActivity.onDestroy] as a safety net. A no-op if it isn't running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ProgrammerService::class.java))
        }
    }
}
