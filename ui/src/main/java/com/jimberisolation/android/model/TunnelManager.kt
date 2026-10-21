/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.jimberisolation.android.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.jimberisolation.android.Application.Companion.get
import com.jimberisolation.android.Application.Companion.getBackend
import com.jimberisolation.android.Application.Companion.getTunnelManager
import com.jimberisolation.android.BR
import com.jimberisolation.android.R
import com.jimberisolation.android.backend.Statistics
import com.jimberisolation.android.backend.Tunnel
import com.jimberisolation.android.configStore.ConfigStore
import com.jimberisolation.android.configStore.CreateTunnelData
import com.jimberisolation.android.configStore.TunnelInfo
import com.jimberisolation.android.daemon.deleteDaemon
import com.jimberisolation.android.databinding.ObservableSortedKeyedArrayList
import com.jimberisolation.android.storage.SharedStorage
import com.jimberisolation.android.util.ErrorMessages
import com.jimberisolation.android.util.UserKnobs
import com.jimberisolation.android.util.applicationScope
import com.jimberisolation.config.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Maintains and mediates changes to the set of available WireGuard tunnels,
 */
class TunnelManager(private val configStore: ConfigStore) : BaseObservable() {
    private val tunnels = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private val context: Context = get()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false
    private val stateMutex = Mutex()

    private suspend fun <T> withStateLock(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { stateMutex.withLock { block() } }

    fun canControlTunnel(tunnel: ObservableTunnel): Boolean {
        val storage = SharedStorage.getInstance()
        return try {
            TunnelAccess.isOwner(
                storage.getCurrentUser()?.id,
                tunnel.getUserId(),
                storage.getDaemonKeyPairByDaemonId(tunnel.getDaemonId())?.userId
            )
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun setBackendState(tunnel: ObservableTunnel, state: Tunnel.State, config: Config?): Tunnel.State =
        TunnelAccess.setState(getBackend(), tunnel, state, config) { canControlTunnel(tunnel) }

    private fun addToList(createTunnelData: CreateTunnelData, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, createTunnelData.companyName, createTunnelData.daemonId, createTunnelData.userId, createTunnelData.deviceName, config, state)

        if(!tunnelMap.containsKey(tunnel.name)) {
            tunnelMap.add(tunnel)
        }

        return tunnel
    }

    suspend fun getTunnels(): ObservableSortedKeyedArrayList<String, ObservableTunnel>  = tunnels.await();

    suspend fun getTunnelsOfUser(): ObservableSortedKeyedArrayList<String, ObservableTunnel> {
        val filteredTunnels = ObservableSortedKeyedArrayList<String, ObservableTunnel>(TunnelComparator)
        filteredTunnels.clear()

        // Ensure tunnels are fully loaded once
        val tunnels = getTunnels()

        for (entry in tunnels) {
            if (canControlTunnel(entry)) {
                filteredTunnels.add(entry)
            }
        }

        return filteredTunnels
    }

    suspend fun create(createTunnelData: CreateTunnelData, config: Config?): ObservableTunnel = withContext(Dispatchers.Main.immediate) {
        val tunnelName = createTunnelData.companyName;

        Log.i("TUNNEL", "IMPORTING TUNNEL WITH NAME ${tunnelName}")

        if (Tunnel.isNameInvalid(tunnelName))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(tunnelName))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, tunnelName))
        addToList(createTunnelData, withContext(Dispatchers.IO) { configStore.create(createTunnelData, config!!) }, Tunnel.State.DOWN)
    }

    suspend fun delete(tunnel: ObservableTunnel) = withStateLock {
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel

        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null

        val sharedStorage = SharedStorage.getInstance();
        try {
            if (originalState == Tunnel.State.UP)
                setBackendState(tunnel, Tunnel.State.DOWN, null)
            try {

                val daemonId =  tunnel.getDaemonId()

                val kp = sharedStorage.getDaemonKeyPairByDaemonId(daemonId)

                // Try to delete, if not, throw no error just continue
                deleteDaemon(tunnel.getDaemonId(), kp!!.companyName, kp.baseEncodedSkEd25519)

                sharedStorage.clearDaemonKeys(tunnel.getDaemonId());

                // Deletion of tunnel
                withContext(Dispatchers.IO) { configStore.delete(tunnel) }
                tunnelMap.remove(tunnel)

            } catch (e: Throwable) {
                sharedStorage.clearDaemonKeys(tunnel.getDaemonId());

                // Deletion of tunnel
                withContext(Dispatchers.IO) { configStore.delete(tunnel) }
                tunnelMap.remove(tunnel)

                throw e
            }
        } catch (e: Throwable) {
            throw e
        }
    }

    suspend fun deleteState(tunnel: ObservableTunnel) = withStateLock {
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel

        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        try {
            if (originalState == Tunnel.State.UP)
                setBackendState(tunnel, Tunnel.State.DOWN, null)
            try {
                withContext(Dispatchers.IO) { configStore.delete(tunnel) }

            } catch (e: Throwable) {
                if (originalState == Tunnel.State.UP)
                    setBackendState(tunnel, Tunnel.State.UP, tunnel.config)
                throw e
            }
        } catch (e: Throwable) {
            // Failure, put the tunnel back.
            tunnelMap.add(tunnel)
            if (wasLastUsed)
                lastUsedTunnel = tunnel.takeIf { canControlTunnel(it) }
            throw e
        }
    }

    @get:Bindable
    var lastUsedTunnel: ObservableTunnel? = null
        private set(value) {
            if (value == field) return
            field = value
            notifyPropertyChanged(BR.lastUsedTunnel)
            applicationScope.launch { UserKnobs.setLastUsedTunnel(value?.name) }
        }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main.immediate) {
        tunnel.onConfigChanged(withContext(Dispatchers.IO) { configStore.load(tunnel) })!!
    }

    fun onCreate() {
        SharedStorage.initialize(context);
        applicationScope.launch {
            try {
                onTunnelsLoaded(withContext(Dispatchers.IO) { configStore.enumerate() }, withContext(Dispatchers.IO) { getBackend().runningTunnelNames })
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    private fun onTunnelsLoaded(present: Iterable<TunnelInfo>, running: Collection<String>) {
        for (info in present) {
            val createTunnelData = CreateTunnelData(info.deviceName, info.daemonId, info.userId, info.companyName)
            print(createTunnelData)
            addToList(createTunnelData,  null, if (running.contains(info.deviceName)) Tunnel.State.UP else Tunnel.State.DOWN)
        }

        applicationScope.launch {
            val lastUsedName = UserKnobs.lastUsedTunnel.first()
            if (lastUsedName != null)
                lastUsedTunnel = tunnelMap[lastUsedName]?.takeIf { canControlTunnel(it) }
            haveLoaded = true
            restoreState(true)
            tunnels.complete(tunnelMap)
        }
    }

    private fun refreshTunnelStates() {
        applicationScope.launch {
            try {
                val running = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }
                for (tunnel in tunnelMap)
                    tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN)
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    suspend fun restoreState(force: Boolean) {
        if (!haveLoaded || (!force && !UserKnobs.restoreOnBoot.first()))
            return
        val previouslyRunning = UserKnobs.runningTunnels.first()
        if (previouslyRunning.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                tunnelMap.filter { previouslyRunning.contains(it.name) && canControlTunnel(it) }.map { async(Dispatchers.IO + SupervisorJob()) { setTunnelState(it, Tunnel.State.UP) } }
                    .awaitAll()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    suspend fun saveState() {
        UserKnobs.setRunningTunnels(tunnelMap.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet())
    }

    suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config = withStateLock {
        setBackendState(tunnel, tunnel.state, config)
        tunnel.onConfigChanged(withContext(Dispatchers.IO) {
            configStore.save(tunnel, config)
        })!!
    }

    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = withStateLock {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name)) {
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        }
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        var throwable: Throwable? = null
        var newName: String? = null
        try {
            if (originalState == Tunnel.State.UP)
                setBackendState(tunnel, Tunnel.State.DOWN, null)
            withContext(Dispatchers.IO) { configStore.rename(tunnel, name) }
            newName = tunnel.onDeviceNameChanged(name)
            if (originalState == Tunnel.State.UP)
                setBackendState(tunnel, Tunnel.State.UP, tunnel.config)
        } catch (e: Throwable) {
            throwable = e
            // On failure, we don't know what state the tunnel might be in. Fix that.
            getTunnelState(tunnel)
        }
        // Add the tunnel back to the manager, under whatever name it thinks it has.
        tunnelMap.add(tunnel)
        if (wasLastUsed)
            lastUsedTunnel = tunnel.takeIf { canControlTunnel(it) }
        if (throwable != null)
            throw throwable
        newName!!
    }

    suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State = withStateLock {
        var newState = tunnel.state
        var throwable: Throwable? = null
        try {
            newState = setBackendState(tunnel, state, tunnel.getConfigAsync())
            if (newState == Tunnel.State.UP)
                lastUsedTunnel = tunnel
        } catch (e: Throwable) {
            newState = tunnel.state
            throwable = e
        }
        tunnel.onStateChanged(newState)
        saveState()
        if (throwable != null)
            throw throwable
        newState
    }

    suspend fun clearUserSession() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        // Revoke access before waiting for an in-flight state change or tunnel loading.
        SharedStorage.getInstance().clearUserLoginData()
        lastUsedTunnel = null
        UserKnobs.setLastUsedTunnel(null)
        val loadedTunnels = getTunnels()
        withStateLock {
            var failure: Exception? = null
            for (tunnel in loadedTunnels) {
                try {
                    tunnel.onStateChanged(setBackendState(tunnel, Tunnel.State.DOWN, tunnel.getConfigAsync()))
                } catch (e: Exception) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
            saveState()
            failure?.let { throw it }
        }
    }

    class IntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            applicationScope.launch {
                val manager = getTunnelManager()
                if (intent == null) return@launch
                val action = intent.action ?: return@launch
                if ("com.jimberisolation.android.action.REFRESH_TUNNEL_STATES" == action) {
                    manager.refreshTunnelStates()
                    return@launch
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !UserKnobs.allowRemoteControlIntents.first())
                    return@launch
                val state: Tunnel.State
                state = when (action) {
                    "com.jimberisolation.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                    "com.jimberisolation.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
                    else -> return@launch
                }
                val tunnelName = intent.getStringExtra("tunnel") ?: return@launch
                val tunnels = manager.getTunnels()
                val tunnel = tunnels[tunnelName] ?: return@launch
                try {
                    manager.setTunnelState(tunnel, state)
                } catch (e: Throwable) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics = withContext(Dispatchers.Main.immediate) {
        tunnel.onStatisticsChanged(withContext(Dispatchers.IO) { getBackend().getStatistics(tunnel) })!!
    }

    companion object {
        private const val TAG = "WireGuard/TunnelManager"
    }
}
