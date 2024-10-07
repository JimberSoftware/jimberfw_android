package com.jimberisolation.android.util


data class Company(
    val name: String
)

data class RefreshResult(
    val accessToken: String,
)

data class UserAuthenticationResult(
    val id: Int,
    val email: String,
    val company: Company
)

data class RouterPublicKeyResult(
    val routerPublicKey: String,
    val ipAddress: String,
    val endpointAddress: String
)

data class GetDaemonsNameResult(
    val name: String
)

data class CreateDaemonData(
    val publicKey: String,
    val name: String
)

data class CreatedDaemonResult(
    val ipAddress: String
)

data class GetEmailVerificationCodeData(
    val email: String,
)

data class EmailVerificationData(
    val email: String,
    val token: Number
)

data class CreateDaemonResult(
    val wireguardConfig: String,
    val company: String
)