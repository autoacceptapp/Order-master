package com.example

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * VercelApiClient
 *
 * Lightweight, production-ready HTTP caller communicating with Vercel Serverless Functions.
 * - Enforces standard HTTP POST requests with JSON payload and Content-Type: application/json.
 * - Extracts current user's Firebase Auth ID token and sends via Authorization: Bearer <token>.
 * - Strictly constructs URLs without trailing slashes (e.g., https://ordermaster-fawn.vercel.app/api/getServerTime).
 * - Prevents default HttpURLConnection redirect downgrading from POST to GET.
 * - Parses and returns JSON responses, throwing informative errors on HTTP failures.
 */
object VercelApiClient {

    private const val TAG = "VercelApiClient"

    // Base URL strictly without trailing slash
    const val BASE_URL: String = "https://ordermaster-fawn.vercel.app/api"

    @Volatile
    var baseUrl: String = BASE_URL
        set(value) {
            val trimmed = value.trim().removeSuffix("/")
            field = trimmed
        }

    /**
     * Resolves the canonical endpoint URL without any trailing slash.
     * E.g., endpoint "getServerTime" -> "https://ordermaster-fawn.vercel.app/api/getServerTime"
     */
    fun buildUrl(endpoint: String): String {
        val cleanEndpoint = endpoint.trim()
            .removePrefix("/")
            .removePrefix("api/")
            .removePrefix("/")
            .removeSuffix(".js")
            .removeSuffix("/")
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val baseWithApi = if (cleanBase.endsWith("/api")) cleanBase else "$cleanBase/api"
        return "$baseWithApi/$cleanEndpoint"
    }

    /**
     * Executes an HTTP POST request to the specified Vercel API endpoint.
     *
     * @param endpoint The route name, e.g. "getServerTime", "addPoints", "purchasePassWithPoints".
     * @param payload Map of parameters to serialize as JSON body.
     * @return Parsed JSONObject response from the server.
     */
    suspend fun post(endpoint: String, payload: Map<String, Any?> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val urlString = buildUrl(endpoint)

        // Retrieve current Firebase ID Token (refreshed if needed)
        val idToken: String? = try {
            val user = FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire Firebase Auth ID token: ${e.message}")
            null
        }

        val jsonBody = JSONObject()
        for ((key, value) in payload) {
            if (value != null) {
                jsonBody.put(key, value)
            }
        }

        executeHttpPost(urlString, jsonBody, idToken)
    }

    private suspend fun executeHttpPost(
        urlString: String,
        jsonBody: JSONObject,
        idToken: String?,
        redirectCount: Int = 0
    ): JSONObject {
        if (redirectCount > 3) {
            throw Exception("Too many HTTP redirects from $urlString")
        }

        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 10000
            doInput = true
            doOutput = true
            // Disable default automatic redirect following to prevent HttpURLConnection
            // from silently downgrading POST into GET on 301/302 redirects
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            if (!idToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $idToken")
            }
        }

        val postData = jsonBody.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(postData.size)

        try {
            conn.outputStream.use { os ->
                os.write(postData)
                os.flush()
            }

            val statusCode = conn.responseCode

            // Handle HTTP 301, 302, 307, 308 redirects while strictly preserving POST
            if (statusCode in listOf(301, 302, 307, 308)) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    Log.d(TAG, "Following redirect ($statusCode) to: $location preserving POST method")
                    val redirectUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    return executeHttpPost(redirectUrl, jsonBody, idToken, redirectCount + 1)
                }
            }

            val isSuccess = statusCode in 200..299
            val stream = if (isSuccess) conn.inputStream else conn.errorStream

            val responseString = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } else {
                ""
            }

            val jsonResponse = try {
                if (responseString.isNotBlank()) JSONObject(responseString) else JSONObject()
            } catch (e: Exception) {
                JSONObject().apply { put("rawResponse", responseString) }
            }

            if (!isSuccess) {
                val errorMsg = jsonResponse.optString(
                    "message",
                    jsonResponse.optString("error", "HTTP error $statusCode from $urlString")
                )
                throw Exception(errorMsg)
            }

            return jsonResponse
        } finally {
            conn.disconnect()
        }
    }
}
