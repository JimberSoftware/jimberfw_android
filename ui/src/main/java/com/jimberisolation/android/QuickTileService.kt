/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.jimberisolation.android

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import com.jimberisolation.android.activity.MainActivity
import com.jimberisolation.android.activity.TunnelToggleActivity
import com.jimberisolation.android.backend.Tunnel
import com.jimberisolation.android.model.ObservableTunnel
import com.jimberisolation.android.util.applicationScope
import com.jimberisolation.android.widget.SlashDrawable
import kotlinx.coroutines.launch

/**
 * Service that maintains the application's custom Quick Settings tile. This service is bound by the
 * system framework as necessary to update the appearance of the tile in the system UI, and to
 * forward click events to the application.
 */
class QuickTileService : TileService() {
    private val onStateChangedCallback = OnStateChangedCallback()
    private val onTunnelChangedCallback = OnTunnelChangedCallback()
    private var iconOff: Icon? = null
    private var iconOn: Icon? = null
    private var tunnel: ObservableTunnel? = null

    /* This works around an annoying unsolved frameworks bug some people are hitting. */
    override fun onBind(intent: Intent): IBinder? {
        var ret: IBinder? = null
        try {
            ret = super.onBind(intent)
        } catch (e: Throwable) {
            Log.d(TAG, "Failed to bind to TileService", e)
        }
        return ret
    }

    override fun onClick() {
        applicationScope.launch {
            if (tunnel == null) {
                Application.getTunnelManager().getTunnels()
            }
            updateTile()
            when (val tunnel = tunnel) {
                null -> openMainActivity()
                else -> {
                    unlockAndRun {
                        applicationScope.launch {
                            try {
                                tunnel.setStateAsync(Tunnel.State.TOGGLE)
                                updateTile()
                            } catch (_: SecurityException) {
                                updateTile()
                                openMainActivity()
                            } catch (_: Throwable) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !Settings.canDrawOverlays(this@QuickTileService)) {
                                    val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                    permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivityAndCollapse(PendingIntent.getActivity(this@QuickTileService, 0, permissionIntent, PendingIntent.FLAG_IMMUTABLE))
                                    return@launch
                                }
                                val toggleIntent = Intent(this@QuickTileService, TunnelToggleActivity::class.java)
                                toggleIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(toggleIntent)
                            }
                        }
                    }
                }
            }
        }
    }

    // Android 11-13 do not have the PendingIntent overload; the branch below guards it.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onCreate() {
        isAdded = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            iconOn = Icon.createWithResource(this, R.drawable.ic_tile)
            iconOff = iconOn
            return
        }
        val icon = SlashDrawable(resources.getDrawable(R.drawable.ic_tile, Application.get().theme))
        icon.setAnimationEnabled(false) /* Unfortunately we can't have animations, since Icons are marshaled. */
        icon.setSlashed(false)
        var b = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
        var c = Canvas(b)
        icon.setBounds(0, 0, c.width, c.height)
        icon.draw(c)
        iconOn = Icon.createWithBitmap(b)
        icon.setSlashed(true)
        b = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
        c = Canvas(b)
        icon.setBounds(0, 0, c.width, c.height)
        icon.draw(c)
        iconOff = Icon.createWithBitmap(b)
    }

    override fun onDestroy() {
        super.onDestroy()
        isAdded = false
    }

    override fun onStartListening() {
        Application.getTunnelManager().addOnPropertyChangedCallback(onTunnelChangedCallback)
        tunnel?.addOnPropertyChangedCallback(onStateChangedCallback)
        updateTile()
    }

    override fun onStopListening() {
        tunnel?.removeOnPropertyChangedCallback(onStateChangedCallback)
        Application.getTunnelManager().removeOnPropertyChangedCallback(onTunnelChangedCallback)
    }

    override fun onTileAdded() {
        isAdded = true
    }

    override fun onTileRemoved() {
        isAdded = false
    }

    private fun updateTile() {
        // Update the tunnel.
        val manager = Application.getTunnelManager()
        val newTunnel = manager.lastUsedTunnel?.takeIf { manager.canControlTunnel(it) }
        if (newTunnel != tunnel) {
            tunnel?.removeOnPropertyChangedCallback(onStateChangedCallback)
            tunnel = newTunnel
            tunnel?.addOnPropertyChangedCallback(onStateChangedCallback)
        }
        // Update the tile contents.
        val tile = qsTile ?: return

        when (val tunnel = tunnel) {
            null -> {
                tile.label = getString(R.string.app_name)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = iconOff
            }
            else -> {
                tile.label = tunnel.name
                tile.state = if (tunnel.state == Tunnel.State.UP) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.icon = if (tunnel.state == Tunnel.State.UP) iconOn else iconOff
            }
        }
        tile.updateTile()
    }

    private inner class OnStateChangedCallback : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable, propertyId: Int) {
            if (sender != tunnel) {
                sender.removeOnPropertyChangedCallback(this)
                return
            }
            if (propertyId != 0 && propertyId != BR.state)
                return
            updateTile()
        }
    }

    private inner class OnTunnelChangedCallback : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable, propertyId: Int) {
            if (propertyId != 0 && propertyId != BR.lastUsedTunnel)
                return
            updateTile()
        }
    }

    companion object {
        private const val TAG = "WireGuard/QuickTileService"
        var isAdded: Boolean = false
            private set
    }
}
