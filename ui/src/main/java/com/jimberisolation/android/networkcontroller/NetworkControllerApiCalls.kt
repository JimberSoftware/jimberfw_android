/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.jimberisolation.android.networkcontroller

import android.util.Log
import com.jimberisolation.android.util.generateSignedMessage
import com.jimberisolation.android.api.requireBody
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder


suspend fun getDaemonConnectionData(daemonId: Int, companyName: String, sk: String): Result<NetworkController> {
    return try {
        val timestampInSeconds = (System.currentTimeMillis() / 1000)
        val timestampBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(timestampInSeconds).array()

        val authorizationHeader = generateSignedMessage(timestampBuffer, sk);

        val response = ApiClient.apiService.getCloudControllerInformation(daemonId, companyName, authorizationHeader)
        val result = response.requireBody()

        val networkController = NetworkController(routerPublicKey = result.routerPublicKey, ipAddress = result.ipAddress, endpointAddress = result.endpointAddress, allowedIps = result.allowedIps)
        Result.success(networkController)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("GET_CLOUD_CONTROLLER_PUBLIC_KEY", "ERROR IN GET_CLOUD_CONTROLLER_PUBLIC_KEY", e)
        Result.failure(e)
    }
}
