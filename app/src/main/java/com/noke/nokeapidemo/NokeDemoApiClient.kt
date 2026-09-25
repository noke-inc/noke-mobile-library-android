package com.noke.nokeapidemo

import android.util.Log
import com.noke.nokemobilelibrary.NokeDevice
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Demo API client for communicating with a sample server.
 *
 * This demonstrates how a third-party app would communicate with its own backend
 * server to facilitate Noke device unlock operations. The mobile app never
 * communicates directly with the Noke Core API — all privileged API calls
 * are made server-side.
 *
 * ## Integration Pattern
 *
 * ```
 * Mobile App              Your Server              Noke Core API
 *     │                       │                          │
 *     │── session + mac ──────▶│                          │
 *     │                       │── unlock request ────────▶│
 *     │                       │◀── commands ─────────────│
 *     │◀── commands ──────────│                          │
 *     │── sendCommands() ─▶ Device                       │
 * ```
 *
 * ## Configuration
 *
 * Set [SERVER_URL] to your backend endpoint before running the demo.
 * The demo server protocol expects:
 *
 * **Request:** `POST /unlock/`
 * ```json
 * { "session": "<session_string>", "mac": "<mac_address>" }
 * ```
 *
 * **Response:** (per the Noke Core API — `result` is the string `"success"`/`"failure"`, not a boolean)
 * ```json
 * { "result": "success", "data": { "commands": "<command_string>" } }
 * ```
 *
 * See the [Noke Mobile Library documentation](https://github.com/noke-inc/noke-mobile-library-android)
 * for full API details.
 */
class NokeDemoApiClient {

    companion object {
        private const val TAG = "NokeDemoApiClient"

        /**
         * Configure this URL to point to your server backend.
         *
         * Example: "https://your-server.example.com/noke/"
         * ALPHA - "https://api-gateway.smartentry.noke.dev/"
         * BETA - "https://api-gateway.smartentry.noke.run/"
         * PRODUCTION - "https://api-gateway.smartentry.noke.com/"
         */
        private const val SERVER_URL = "https://api-gateway.smartentry.noke.dev/"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000

        private val executor = Executors.newCachedThreadPool()
    }

    /**
     * Requests unlock commands from the server for a connected Noke device.
     *
     * Sends the device session token and MAC to your server. The server validates
     * the request with the Noke Core API and returns a command string that is
     * sent directly to the device via [NokeDevice.sendCommands].
     *
     * The server response may also include `offline_key` and `offline_unlock_cmd` fields.
     * When present, these are surfaced via [onSuccess] so the caller can cache them on
     * the [NokeDevice] via [NokeDevice.setOfflineKey] and [NokeDevice.setOfflineUnlockCmd].
     *
     * @param noke Connected Noke device whose session will be sent
     * @param onSuccess Invoked on the calling thread with the command string, and optionally the
     *   offline key and offline unlock command returned by the server (may be null if not provided)
     * @param onError Invoked on the calling thread with a human-readable error message
     */
    fun requestUnlock(
        noke: NokeDevice,
        onSuccess: (commandString: String, offlineKey: String?, offlineUnlockCmd: String?) -> Unit,
        onError: (message: String) -> Unit
    ) {
        executor.execute {
            try {
                val payload = JSONObject().apply {
                    put("session", noke.session)
                    put("mac", noke.mac)
                }
                val response = post("${SERVER_URL}unlock/", payload)
                parseUnlockResponse(response, onSuccess, onError)
            } catch (e: MalformedURLException) {
                Log.e(TAG, "Invalid server URL: $SERVER_URL", e)
                onError("Server URL is not configured. Set SERVER_URL in NokeDemoApiClient.")
            } catch (e: IOException) {
                Log.e(TAG, "Network error during unlock request", e)
                onError("Network error: ${e.message}")
            } catch (e: JSONException) {
                Log.e(TAG, "JSON error building unlock request", e)
                onError("Request error: ${e.message}")
            }
        }
    }

    /**
     * Requests fob sync commands from the server.
     *
     * Used for Noke Fob devices (hardware version contains "f").
     *
     * @param noke Connected Noke fob device
     * @param onSuccess Invoked with the command string
     * @param onError Invoked with an error message
     */
    fun requestFobSync(
        noke: NokeDevice,
        onSuccess: (commandString: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        executor.execute {
            try {
                val payload = JSONObject().apply {
                    put("session", noke.session)
                    put("mac", noke.mac)
                }
                val response = post("${SERVER_URL}fobs/sync/", payload)
                parseCommandsFromResponse(response, onSuccess, onError)
            } catch (e: IOException) {
                Log.e(TAG, "Network error during fob sync", e)
                onError("Network error: ${e.message}")
            } catch (e: JSONException) {
                Log.e(TAG, "JSON error building fob sync request", e)
                onError("Request error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses unlock commands and optional offline key data from the server response JSON.
     *
     * Expected response format:
     * ```json
     * {
     *   "result": "success",
     *   "data": {
     *     "commands": "<command_string>",
     *     "offline_key": "<32-char hex>",        // optional
     *     "offline_unlock_cmd": "<40-char hex>"  // optional
     *   }
     * }
     * ```
     *
     * On failure the Noke Core API returns `result: "failure"` along with `errorCode` and
     * `message` fields (e.g. `errorCode: 55, message: "Invalid endpoint..."`), so `result`
     * must be read as a String — not a boolean — or every failure response throws a
     * `JSONException` that masks the real server-provided error.
     */
    private fun parseUnlockResponse(
        responseBody: String,
        onSuccess: (commandString: String, offlineKey: String?, offlineUnlockCmd: String?) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val json = JSONObject(responseBody)
            if (json.optString("result") == "success") {
                val data = json.getJSONObject("data")
                val commands = data.getString("commands")
                val offlineKey = data.optString("offline_key", null)
                val offlineUnlockCmd = data.optString("offline_unlock_cmd", null)
                onSuccess(commands, offlineKey, offlineUnlockCmd)
            } else {
                val errorCode = json.optInt("errorCode", -1)
                val errorMessage = json.optString("message", "Access denied")
                Log.w(TAG, "Server denied unlock: errorCode=$errorCode message=$errorMessage")
                onError(if (errorCode != -1) "$errorMessage (errorCode $errorCode)" else errorMessage)
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse server response: $responseBody", e)
            onError("Invalid server response")
        }
    }

    /**
     * Parses unlock commands from the server response JSON.
     *
     * Expected response format:
     * ```json
     * { "result": "success", "data": { "commands": "<command_string>" } }
     * ```
     */
    private fun parseCommandsFromResponse(
        responseBody: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val json = JSONObject(responseBody)
            if (json.optString("result") == "success") {
                val commands = json.getJSONObject("data").getString("commands")
                onSuccess(commands)
            } else {
                val errorCode = json.optInt("errorCode", -1)
                val errorMessage = json.optString("message", "Access denied")
                Log.w(TAG, "Server denied unlock: errorCode=$errorCode message=$errorMessage")
                onError(if (errorCode != -1) "$errorMessage (errorCode $errorCode)" else errorMessage)
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse server response: $responseBody", e)
            onError("Invalid server response")
        }
    }

    /**
     * Performs an HTTP POST request with a JSON body.
     *
     * @param urlString Full URL to POST to
     * @param body JSON payload
     * @return Response body as a string
     * @throws IOException on network failure
     */
    private fun post(urlString: String, body: JSONObject): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
            }

            val bodyBytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(bodyBytes) }
            connection.connect()

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw IOException("HTTP $responseCode with no error body")
            }

            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } finally {
            connection.disconnect()
        }
    }
}
