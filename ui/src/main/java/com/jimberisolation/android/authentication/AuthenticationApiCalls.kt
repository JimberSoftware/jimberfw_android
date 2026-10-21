/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.jimberisolation.android.authentication

import android.util.Log
import com.jimberisolation.android.storage.SharedStorage
import com.jimberisolation.android.util.getCookieString
import com.jimberisolation.android.util.saveDataToLocalStorage
import com.jimberisolation.android.api.requireBody
import kotlinx.coroutines.CancellationException

suspend fun getUserAuthentication(idToken: String, authenticationType: AuthenticationType): Result<UserAuthentication> {
    val type = when (authenticationType) {
        AuthenticationType.Google -> "google"
        AuthenticationType.Microsoft -> "microsoft"
    }

    return try {
        val authRequest = AuthenticationApiRequest(idToken)

        val response = ApiClient.apiService.getUserAuthentication(type, authRequest)
        val result = response.requireBody()
        val cookies = response.headers().values("Set-Cookie")

        val user = User(result.id, result.email)
        saveDataToLocalStorage(cookies.joinToString("; "), user)

        val userAuthentication = UserAuthentication(userId = result.id, companyName = result.company.name)
        Result.success(userAuthentication)

    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("USER_AUTHENTICATION", "ERROR IN USER AUTHENTICATION", e)
        Result.failure(e)
    }
}

suspend fun refreshToken(): Result<AuthenticationToken> {
    return try {
        val cookies = getCookieString()

        val response = ApiClient.apiService.refreshToken(cookies)
        val result = response.requireBody()
        SharedStorage.getInstance().saveAuthenticationToken(result.accessToken)

        val authenticationToken = AuthenticationToken(result.accessToken)
        Result.success(authenticationToken)

    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("REFRESH_TOKEN", "ERROR IN GET REFRESH TOKEN", e)
        Result.failure(e)
    }
}


suspend fun logout(): Result<Boolean> {
    return try {
        val cookies = getCookieString()

        val response = ApiClient.apiService.logout(cookies)
        val result = response.requireBody()
        Result.success(result)

    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("LOGOUT", "ERROR IN LOGOUT", e)
        Result.failure(e)
    }
}