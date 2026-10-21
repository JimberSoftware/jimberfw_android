/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.jimberisolation.android.daemon

import android.util.Log
import com.jimberisolation.android.util.generateSignedMessage
import com.jimberisolation.android.util.getCookieString
import com.jimberisolation.android.api.requireBody
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder

suspend fun createDaemon(userId: Int, company: String, daemonData: CreateDaemonApiRequest): Result<Daemon> {
    return try {
        val cookies = getCookieString()

        val response = ApiClient.apiService.createDaemon(userId, company, daemonData, cookies)
        val result = response.requireBody()
        val daemon = Daemon(daemonId = result.id, ipAddress = result.ipAddress, name = result.name)

        Result.success(daemon)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("CREATE_DAEMON", "ERROR IN CREATE DAEMON", e)
        Result.failure(e)
    }
}

suspend fun deleteDaemon(daemonId: Number, company: String, sk: String): Result<DeletedDaemon> {
    return try {
        val timestampInSeconds = (System.currentTimeMillis() / 1000)
        val timestampBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(timestampInSeconds).array()

        val authorizationHeader = generateSignedMessage(timestampBuffer, sk);


        val response = ApiClient.apiService.deleteDaemon(daemonId, company, authorizationHeader)
        val result = response.requireBody()
        val daemon =  DeletedDaemon(daemonId = result.id)

        Result.success(daemon)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("CREATE_DAEMON", "ERROR IN DELETE DAEMON", e)
        Result.failure(e)
    }
}

suspend fun getDaemonInfo(daemonId: Number, company: String, sk: String): Result<DaemonInfo> {
    return try {
        val timestampInSeconds = (System.currentTimeMillis() / 1000)
        val timestampBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(timestampInSeconds).array()

        val authorizationHeader = generateSignedMessage(timestampBuffer, sk);

        val response = ApiClient.apiService.getDaemonInformation(daemonId, company, authorizationHeader)
        val result = response.requireBody()

        var isApproved = false;
        if(result.approvalStatus == "approved") {
            isApproved = true;
        }

        val daemon =  DaemonInfo(daemonId = result.id, name = result.name, isApproved = isApproved)

        Result.success(daemon)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("CREATE_DAEMON", "ERROR IN DELETE DAEMON", e)
        Result.failure(e)
    }
}