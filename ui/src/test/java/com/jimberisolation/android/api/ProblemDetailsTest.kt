package com.jimberisolation.android.api

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ProblemDetailsTest {
    @Test fun customProblemPreservesAllFieldsAndDisplaysDetail() {
        val error = parseApiException(400, """{
            "type":"urn:jimber:problem:bad-request", "title":"Bad Request", "status":400,
            "instance":"/api/v1/companies/Test/groups/444", "detail":"Group with id 444 not found",
            "code":"GROUP_MISSING", "correlationId":"request-123"
        }""")
        assertEquals("Group with id 444 not found", error.userFacingMessage())
        assertEquals("urn:jimber:problem:bad-request", error.problem!!.type)
        assertEquals(400, error.problem.status)
        assertEquals("/api/v1/companies/Test/groups/444", error.problem.instance)
        assertEquals("GROUP_MISSING", error.problem.code)
        assertEquals("request-123", error.problem.correlationId)
    }

    @Test fun prismaConflictWithoutDetailUsesTitleAndDoesNotGuessFieldFromIndexName() {
        val error = parseApiException(409, """{
            "type":"urn:jimber:problem:already-exists", "title":"Conflict", "status":409,
            "errors":[{"field":"user_user_id_key","detail":""}]
        }""")
        assertEquals("Conflict", error.userFacingMessage())
        assertNull(error.fieldError("email", "name"))
        assertFalse(error.userFacingMessage().contains("user_user_id_key"))
    }

    @Test fun fieldValidationMapsToEmailAndTokenSeparately() {
        val error = parseApiException(422, """{
            "type":"urn:jimber:problem:validation-error", "status":422, "detail":"Validation failed",
            "errors":[{"field":"email","detail":"Email is taken","code":"unique"},
                      {"field":"token","detail":"Code has expired"}]
        }""")
        assertEquals("Email is taken", error.fieldError("email"))
        assertEquals("Code has expired", error.fieldError("token"))
        assertNull(error.fieldError("name"))
        assertEquals("unique", error.problem!!.errors[0].code)
    }

    @Test fun jsonPointersMapToFieldsIncludingEscapedNames() {
        val error = parseApiException(400, """{"errors":[
            {"pointer":"/body/name","detail":"Invalid device name"},
            {"pointer":"/email","detail":"Invalid email"},
            {"pointer":"/a~1b~0c","detail":"Invalid escaped field"},
            {"pointer":"/unrelated/name","detail":"Do not map this"}]}""")
        assertEquals("Invalid device name", error.fieldError("name"))
        assertEquals("Invalid email", error.fieldError("email"))
        assertEquals("Invalid escaped field", error.fieldError("a/b~c"))
    }

    @Test fun nestValidationWithoutFieldStillDisplaysEveryMessage() {
        val error = parseApiException(400, """{"detail":"Validation failed","errors":[
            {"detail":"email must be an email"}, {"detail":"token must be a number"},
            {"detail":"email must be an email"}]}""")
        assertEquals("Validation failed\nemail must be an email\ntoken must be a number", error.userFacingMessage())
        assertNull(error.fieldError("email"))
    }

    @Test fun uniqueFieldWithEmptyDetailHasUsefulInlineFallback() {
        val error = parseApiException(409, """{"type":"urn:jimber:problem:already-exists",
            "title":"Conflict","errors":[{"field":"name","detail":""}]}""")
        assertEquals("This value is already in use.", error.fieldError("name"))
    }

    @Test fun actualHttpStatusControlsSessionAndDeviceRemovalDecisions() {
        val error = parseApiException(500, """{"status":403,"detail":"Temporarily unavailable"}""")
        assertEquals(500, error.status)
        assertEquals(403, error.problem!!.status)
        assertFalse(error.hasHttpStatus(401, 403, 404))
        assertTrue(error.hasHttpStatus(500))
        assertTrue(parseApiException(404, "{}").hasHttpStatus(403, 404))
    }

    @Test fun htmlEmptyOrMalformedResponsesRetainStatusAndNeverLeakRawBody() {
        for (body in listOf(null, "", "null", "[]", "false", "<html>proxy secret</html>", "{broken")) {
            val error = parseApiException(502, body)
            assertEquals(502, error.status)
            assertNull(error.problem)
            assertEquals("The server could not process the request. Please try again later.", error.userFacingMessage())
        }
    }

    @Test fun malformedOptionalFieldsDoNotHideValidDetail() {
        val error = parseApiException(422, """{"type":42,"status":"422","title":{},
            "detail":"Fix your input","errors":[null,5,{"field":true,"detail":[]}]}""")
        assertEquals("about:blank", error.problem!!.type)
        assertNull(error.problem.status)
        assertNull(error.problem.title)
        assertEquals("Fix your input", error.userFacingMessage())
        assertNull(error.fieldError("email"))
    }

    @Test fun unknownProblemTypesStillDisplayDetail() {
        for (type in listOf("about:blank", "urn:jimber:problem:foreign-key-constraint", "urn:future:type")) {
            val error = parseApiException(400, """{"type":"$type","detail":"Related resource is missing"}""")
            assertEquals("Related resource is missing", error.userFacingMessage())
        }
    }

    @Test fun legacyFieldsAreNotUsedAfterCutover() {
        val error = parseApiException(429, """{"message":"old message","error":"old title","statusCode":403}""")
        assertEquals("Too many requests. Please wait and try again.", error.userFacingMessage())
        assertNull(error.problem!!.status)
        assertFalse(error.hasHttpStatus(403))
    }

    @Test fun blankDetailsAndAbsentErrorsUseSafeFallback() {
        for (status in listOf(400, 401, 403, 404, 409, 422, 429, 500, 503)) {
            val error = parseApiException(status, """{"detail":" ","title":"","errors":null}""")
            assertTrue(error.userFacingMessage().isNotBlank())
            assertEquals(emptyList<ProblemFieldError>(), error.problem!!.errors)
        }
    }

    @Test fun retrofitErrorIsConvertedAndSuccessIsUntouched() {
        val response = Response.error<Boolean>(403,
            """{"status":403,"detail":"Device limit reached"}""".toResponseBody("application/problem+json".toMediaType()))
        val error = assertThrows(ApiException::class.java) { response.requireBody() }
        assertEquals("Device limit reached", error.userFacingMessage())
        assertEquals(true, Response.success(true).requireBody())
        assertEquals(false, Response.success(false).requireBody())
        assertThrows(ApiException::class.java) { Response.success<Boolean>(null).requireBody() }
    }

    @Test fun transportFailuresShowHelpfulMessagesWithoutInternalExceptionText() {
        assertEquals("The request timed out. Please try again.", SocketTimeoutException("internal").userFacingMessage())
        assertEquals("Could not connect to the server. Check your connection and try again.", IOException("private host").userFacingMessage())
        assertEquals("Something went wrong. Please try again.", IllegalStateException("internal").userFacingMessage())
    }
}
