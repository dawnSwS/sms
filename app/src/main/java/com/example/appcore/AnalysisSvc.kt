package com.example.appcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AnalysisSvc : Service() {

    companion object {
        private const val API_ENDPOINT = "https://gateway.ai.cloudflare.com/v1/c737e370b33f6767c378950f940d1fde/sms/compat/chat/completions"
        private const val TIMEOUT_MS = 600000
        private const val TAG = "AnalysisServiceAI"
        
        private const val MAX_RETRIES = 10 
        private const val CACHE_EXPIRATION_MS = 3600000L
        
        private val cachePool = ConcurrentHashMap<Int, Pair<Long, Boolean>>()
        
        private fun logDebug(msg: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[DEBUG] $msg")
        }

        private fun logError(msg: String, t: Throwable? = null) {
            Log.e(TAG, "[ERROR] $msg", t)
        }
    }

    private val executor = Executors.newCachedThreadPool()

    private val decodedApiKey: String by lazy {
        String(Base64.decode(BuildConfig.B64_KEY, Base64.NO_WRAP), Charsets.UTF_8)
    }
    
    private val decodedInstruction: String by lazy {
        String(Base64.decode(BuildConfig.B64_INSTR, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private val binder = object : IAnalysisSvc.Stub() {
        override fun processDataAsync(rawData: String, cb: IAnalysisCb?) {
            val callingUid = Binder.getCallingUid()
            
            if (!isCallerAuthorized(callingUid)) {
                logError("Unauthorized IPC call intercepted. UID: $callingUid")
                throw SecurityException("Access Denied: IPC caller signature is invalid.")
            }

            logDebug("processDataAsync invoked. UID: $callingUid, Data length: ${rawData.length}")
            
            var wakeLock: PowerManager.WakeLock? = null
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppCore:AI_WakeLock")
                wakeLock.acquire(35 * 60 * 1000L)
            } catch (e: Exception) {
                logError("Failed to acquire wakelock synchronously", e)
            }

            executor.submit {
                try {
                    val isSpam = executeAnalysisTask(rawData)
                    cb?.onResult(isSpam)
                } catch (e: Exception) {
                    logError("Async task execution failed", e)
                    runCatching { cb?.onResult(false) }
                } finally {
                    runCatching {
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    }
                }
            }
        }
    }

    private fun isCallerAuthorized(callingUid: Int): Boolean {
        return callingUid == 1000 || 
               callingUid == 1001 || 
               callingUid == Process.myUid() || 
               packageManager.getPackagesForUid(callingUid)?.contains("com.android.phone") == true
    }

    override fun onBind(intent: Intent?): IBinder {
        logDebug("Service started, Binder connection established.")
        return binder
    }

    override fun onDestroy() {
        logDebug("Service terminating, cleaning up resources.")
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun executeAnalysisTask(inputData: String): Boolean {
        if (inputData.isBlank()) return false

        val dataHash = inputData.hashCode()
        val currentTime = System.currentTimeMillis()
        
        val cachedResult = cachePool[dataHash]
        if (cachedResult != null && (currentTime - cachedResult.first < CACHE_EXPIRATION_MS)) {
            logDebug("Cache hit. Returning cached result: ${cachedResult.second}")
            return cachedResult.second
        }

        var lastErrorMessage = "Unknown system error"
        var delayMs = 15000L
        
        for (attempt in 1..MAX_RETRIES) {
            try {
                logDebug("Establishing network connection... (Attempt: $attempt/$MAX_RETRIES)")
                val url = URL(API_ENDPOINT)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    connectTimeout = 30000 
                    readTimeout = TIMEOUT_MS
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $decodedApiKey")
                    setRequestProperty("Connection", "Keep-Alive")
                    setRequestProperty("cf-aig-cache-bypass", "false")
                    doOutput = true
                }

                val payload = buildRequestPayload(inputData)
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }

                val responseCode = connection.responseCode
                logDebug("HTTP request completed. Status code: $responseCode")
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    return parseAndValidateResponse(responseText, dataHash, currentTime)
                } 
                else if (responseCode == HttpURLConnection.HTTP_UNAVAILABLE || responseCode == 429) {
                    val errorResponse = runCatching { 
                        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } 
                    }.getOrNull() ?: "No detailed error stream provided."
                    
                    lastErrorMessage = "Server High Demand (HTTP $responseCode). Details: $errorResponse"
                    logError("Attempt $attempt blocked by server limit: $lastErrorMessage")
                    
                    if (attempt < MAX_RETRIES) {
                        logDebug("Sleeping for ${delayMs / 1000}s before next retry...")
                        Thread.sleep(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(300000L) 
                        continue
                    }
                } 
                else {
                    val errorResponse = runCatching { 
                        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } 
                    }.getOrNull() ?: "No detailed error stream provided."
                    
                    lastErrorMessage = "HTTP Request failed. Status Code: $responseCode. Details: $errorResponse"
                    logError("Attempt $attempt failed: $lastErrorMessage")
                    
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(5000)
                    }
                }
            } catch (e: JSONException) {
                lastErrorMessage = "JSON Validation Error: ${e.message}"
                logError("Attempt $attempt failed during validation: $lastErrorMessage")
                break
            } catch (e: Exception) {
                lastErrorMessage = "Execution exception: ${e.javaClass.simpleName} - ${e.message}"
                logError("Attempt $attempt encountered an exception: $lastErrorMessage", e)
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(300000L)
                }
            }
        }
        
        logError("Maximum retry limit ($MAX_RETRIES) reached. API analysis failed.")
        triggerErrorNotification(lastErrorMessage, inputData)
        
        return false 
    }
    
    private fun buildRequestPayload(inputData: String): JSONObject {
        return JSONObject().apply {
            put("model", "google-ai-studio/gemini-3.1-flash-lite-preview")
            put("stream", false)
            put("temperature", 1.0)
            put("reasoning_effort", "high")
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", decodedInstruction)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", inputData)
                })
            })
        }
    }

    @Throws(JSONException::class)
    private fun parseAndValidateResponse(responseText: String, dataHash: Int, currentTime: Long): Boolean {
        logDebug("Raw API Response: $responseText")
        
        val contentString = JSONObject(responseText)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content", "{}") ?: "{}"
        
        val sanitizedContent = contentString.replace(Regex("^```json\\s*|```$"), "").trim()
        val aiResult = JSONObject(sanitizedContent)
        
        if (!aiResult.has("is")) {
            throw JSONException("Missing required boolean field 'is' in JSON response.")
        }
        
        val isValue = aiResult.get("is")
        if (isValue !is Boolean) {
            throw JSONException("Field 'is' must be of type Boolean. Received: ${isValue.javaClass.simpleName}")
        }
        
        logDebug("Model analysis successful -> is: $isValue")
        cachePool[dataHash] = Pair(currentTime, isValue)
        return isValue
    }
    
    private fun triggerErrorNotification(errorMessage: String, rawData: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "ai_analysis_error_channel"
            
            val channel = NotificationChannel(
                channelId,
                "AI Analysis Engine Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts triggered when the AI analysis module encounters consecutive critical failures."
            }
            notificationManager.createNotificationChannel(channel)
            
            val truncatedData = if (rawData.length > 50) "${rawData.substring(0, 50)}..." else rawData
            val notificationContent = "Error Reason:\n$errorMessage\n\nTriggering Data:\n$truncatedData"
            
            val notification = Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Analysis Service: API Request Failed")
                .setContentText("The analysis engine encountered a failure. Expand for details.")
                .setStyle(Notification.BigTextStyle().bigText(notificationContent))
                .setAutoCancel(true)
                .build()
                
            val notificationId = (System.currentTimeMillis() % 100000).toInt()
            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            logError("Failed to dispatch error notification", e)
        }
    }
}