package com.example

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * VercelApiClient
 *
 * Lightweight, production-ready HTTP caller communicating with Vercel Serverless Functions.
 * - Extracts current user's Firebase Auth ID token and sends via Authorization: Bearer <token>.
 * - Sends standard HTTP POST requests with JSON payload.
 * - Parses and returns JSON responses, throwing informative errors on HTTP failures.
 */
object VercelApiClient {

    private const val TAG = "VercelApiClient"

    // Default Vercel production deployment URL.
    @Volatile
    var baseUrl: String = "https://ordermaster-fawn.vercel.app"

    /**
     * Executes an HTTP POST request to the specified Vercel API endpoint.
     *
     * @param endpoint The route name, e.g. "getServerTime", "addPoints", "purchasePassWithPoints".
     * @param payload Map of parameters to serialize as JSON body.
     * @return Parsed JSONObject response from the server.
     */
    suspend fun post(endpoint: String, payload: Map<String, Any?> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val cleanEndpoint = endpoint.trim().removePrefix("/").removePrefix("api/")
        val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/api")
        val urlString = "$cleanBase/api/$cleanEndpoint"
        val url = URL(urlString)

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

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            if (!idToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $idToken")
            }
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            val statusCode = conn.responseCode
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
                    jsonResponse.optString("error", "HTTP error $statusCode")
                )
                throw Exception(errorMsg)
            }

            jsonResponse
        } finally {
            conn.disconnect()
        }
    }
}
