/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.jimberisolation.android.authentication

import android.util.Log
import com.jimberisolation.android.util.saveDataToLocalStorage
import com.jimberisolation.android.api.requireBody
import kotlinx.coroutines.CancellationException

suspend fun sendVerificationEmail(email: String): Result<Boolean> {
    return try {
        val verificationCodeApiRequest = VerificationCodeApiRequest(email)

        val response = ApiClient.apiService.sendVerificationEmail(verificationCodeApiRequest)
        val result = response.requireBody()
        Result.success(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("EMAIL_VERIFICATION_CODE", "ERROR IN EMAIL VERIFICATION CODE REQUEST", e)
        Result.failure(e)
    }
}

suspend fun verifyEmailWithToken(emailVerificationData: AuthenticationWithVerificationCodeApiRequest): Result<UserAuthentication> {
    return try {
        val response = ApiClient.apiService.verifyEmailWithToken(emailVerificationData)

        val result = response.requireBody()
        val cookies = response.headers().values("Set-Cookie")

        val user = User(result.id, result.email)
        saveDataToLocalStorage(cookies.joinToString("; "), user)

        val userAuthentication = UserAuthentication(userId = result.id, companyName = result.company.name)
        Result.success(userAuthentication)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("EMAIL_WITH_TOKEN", "ERROR IN EMAIL VERIFY WITH CODE CODE REQUEST", e)
        Result.failure(e)
    }
}
