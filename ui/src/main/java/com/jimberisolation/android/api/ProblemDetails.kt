package com.jimberisolation.android.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import retrofit2.Response

data class ProblemFieldError(
    val detail: String?,
    val field: String? = null,
    val pointer: String? = null,
    val code: String? = null
)

data class ProblemDetails(
    val type: String,
    val title: String?,
    val status: Int?,
    val detail: String?,
    val instance: String?,
    val errors: List<ProblemFieldError> = emptyList(),
    val code: String? = null,
    val correlationId: String? = null
)

class ApiException(val status: Int, val problem: ProblemDetails?) : IOException(displayMessage(status, problem)) {
    fun messageForField(vararg names: String): String? {
        val matching = problem?.errors.orEmpty().filter { error ->
            error.field in names || error.pointer?.let { pointer ->
                // Accept a field pointer, optionally under the request body.
                val parts = pointer.split('/').drop(1).map { it.replace("~1", "/").replace("~0", "~") }
                pointer.startsWith('/') && (parts.size == 1 || (parts.size == 2 && parts[0] == "body")) && parts.last() in names
            } == true
        }
        if (matching.isEmpty()) return null
        return matching.map { error ->
            error.detail?.takeIf(String::isNotBlank)
                ?: if (problem?.type == "urn:jimber:problem:already-exists") "This value is already in use."
                else message.orEmpty()
        }.distinct().joinToString("\n")
    }
}

private fun displayMessage(status: Int, problem: ProblemDetails?): String =
    (listOfNotNull(problem?.detail?.takeIf(String::isNotBlank)) +
        problem?.errors.orEmpty().mapNotNull { it.detail?.takeIf(String::isNotBlank) })
        .distinct().joinToString("\n").takeIf(String::isNotBlank)
        ?: problem?.title?.takeIf(String::isNotBlank)
        ?: fallbackMessage(status)

fun Throwable?.hasHttpStatus(vararg statuses: Int): Boolean = this is ApiException && status in statuses

fun Throwable?.fieldError(vararg names: String): String? = (this as? ApiException)?.messageForField(*names)

fun Throwable?.userFacingMessage(): String = when (this) {
    is ApiException -> message ?: fallbackMessage(status)
    is SocketTimeoutException -> "The request timed out. Please try again."
    is IOException -> "Could not connect to the server. Check your connection and try again."
    else -> "Something went wrong. Please try again."
}

fun <T> Response<T>.requireBody(): T {
    if (!isSuccessful) {
        // Retain the HTTP status even when a proxy sends HTML or no body at all.
        val rawBody = try { errorBody()?.use { it.string() } } catch (_: IOException) { null }
        throw parseApiException(code(), rawBody)
    }
    return body() ?: throw ApiException(code(), null)
}

internal fun parseApiException(httpStatus: Int, body: String?): ApiException {
    val problem = try {
        val root = Gson().fromJson(body, JsonElement::class.java)
        if (root == null || !root.isJsonObject) null else {
            val json = root.asJsonObject
            ProblemDetails(
                type = json.string("type") ?: "about:blank",
                title = json.string("title"),
                status = json.get("status")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asString?.toIntOrNull()?.takeIf { it in 100..599 },
                detail = json.string("detail"),
                instance = json.string("instance"),
                errors = json.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { entry ->
                    if (!entry.isJsonObject) null else entry.asJsonObject.let {
                        ProblemFieldError(it.string("detail"), it.string("field"), it.string("pointer"), it.string("code"))
                    }
                }.orEmpty(),
                code = json.string("code"),
                correlationId = json.string("correlationId")
            )
        }
    } catch (_: RuntimeException) {
        null
    }
    // The status line controls behaviour; a malformed/mismatched body cannot turn a 500 into a 403.
    return ApiException(httpStatus, problem)
}

private fun JsonObject.string(name: String): String? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun fallbackMessage(status: Int): String = when (status) {
    400, 422 -> "Please check the entered information and try again."
    401 -> "Your session is invalid or expired. Please sign in again."
    403 -> "You do not have permission to perform this action."
    404 -> "The requested resource could not be found."
    409 -> "The request conflicts with existing data."
    429 -> "Too many requests. Please wait and try again."
    in 500..599 -> "The server could not process the request. Please try again later."
    else -> "The server returned an unexpected response. Please try again."
}
