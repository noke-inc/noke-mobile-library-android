package com.noke.nokemobilelibrary.phonekey.internal

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SecurityServiceRetryInterceptorTest {

    private val authHeaders = mutableListOf<String?>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun execute(statusCodes: List<Int>, tokens: List<String>): Response {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        var tokenIndex = 0
        val retry = SecurityServiceRetryInterceptor(
            context = context,
            authTokenProvider = { tokens[tokenIndex++] },
            maxRetries = 1,
            retryDelayMs = 0,
            tokenScheme = "ApiKey"
        )

        var call = 0
        val terminal = Interceptor { chain ->
            authHeaders.add(chain.request().header("Authorization"))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(statusCodes[call++])
                .message("test")
                .body("".toResponseBody(null))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(retry).addInterceptor(terminal).build()
        val request = Request.Builder()
            .url("https://example.test/nse-phone-acl/api/v1/acls")
            .header("Authorization", "ApiKey initial")
            .build()
        return client.newCall(request).execute()
    }

    @Test
    fun forbiddenIsNotRetried() {
        val response = execute(listOf(403), emptyList())

        assertEquals(403, response.code)
        assertEquals(listOf<String?>("ApiKey initial"), authHeaders)
    }

    @Test
    fun unauthorizedIsRetriedOnceWithFreshToken() {
        val response = execute(listOf(401, 200), listOf("fresh"))

        assertEquals(200, response.code)
        assertEquals(listOf<String?>("ApiKey initial", "ApiKey fresh"), authHeaders)
    }

    @Test
    fun serverErrorIsRetriedOnceWithOriginalRequest() {
        val response = execute(listOf(503, 503), emptyList())

        assertEquals(503, response.code)
        assertEquals(listOf<String?>("ApiKey initial", "ApiKey initial"), authHeaders)
    }
}
