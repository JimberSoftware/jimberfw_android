package com.jimberisolation.android.model

import com.jimberisolation.android.backend.Backend
import com.jimberisolation.android.backend.Tunnel
import com.jimberisolation.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal object TunnelAccess {
    fun isOwner(currentUserId: Int?, tunnelUserId: Int, keyPairUserId: Int?): Boolean =
        currentUserId != null && currentUserId == tunnelUserId && currentUserId == keyPairUserId

    suspend fun setState(
        backend: Backend,
        tunnel: Tunnel,
        state: Tunnel.State,
        config: Config?,
        isAuthorized: () -> Boolean
    ): Tunnel.State = withContext(NonCancellable) {
        // DOWN remains available for logout and cleanup, even without a session.
        // Never pass an unauthorized TOGGLE to the backend: it may resolve to UP.
        val result = withContext(Dispatchers.IO) {
            if (state != Tunnel.State.DOWN && !isAuthorized()) {
                throw SecurityException("Sign in as the owner of this tunnel to activate it.")
            }
            backend.setState(tunnel, state, config)
        }
        // A session may have changed while the backend was starting the tunnel.
        if (result == Tunnel.State.UP && !isAuthorized()) {
            withContext(NonCancellable + Dispatchers.IO) {
                backend.setState(tunnel, Tunnel.State.DOWN, config)
            }
            throw SecurityException("The tunnel owner is no longer signed in.")
        }
        result
    }
}
