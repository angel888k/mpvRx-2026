package app.gyrolet.mpvrx.ui.player.curl

import android.util.Log
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class CurlBridge(
  private val scope: CoroutineScope,
  private val responseSender: CurlResponseSender = MpvCurlResponseSender,
  private val baseClient: OkHttpClient = defaultClient(),
) {
  private val unsafeTrustManager by lazy {
    object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
      override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
  }

  private val unsafeSslSocketFactory by lazy {
    SSLContext.getInstance("TLS").apply {
      init(null, arrayOf<TrustManager>(unsafeTrustManager), SecureRandom())
    }.socketFactory
  }

  fun handleRequest(requestJson: String) {
    scope.launch(Dispatchers.IO) {
      var callback = CurlCallback()
      try {
        val rawJson = normalizeJsonString(requestJson)
        if (rawJson.isBlank()) {
          Log.w(TAG, "curl request skipped: empty request")
          return@launch
        }
        val requestObject = JSONObject(rawJson)
        callback = CurlCallback.from(requestObject)

        val responseJson = executeRequest(requestObject, callback.id)
        sendResponse(callback, responseJson)
      } catch (error: Exception) {
        Log.e(TAG, "curl request failed", error)
        if (callback.messageName.isNotBlank()) {
          sendResponse(callback, errorResponse(callback.id, error.message ?: "Unknown curl error"))
        }
      }
    }
  }

  private fun executeRequest(
    requestObject: JSONObject,
    requestId: String,
  ): JSONObject {
    val url = requestObject.getString("url").trim()
    require(url.isNotEmpty()) { "url is required" }

    val method = requestObject.optString("method", "GET").uppercase(Locale.US)
    val timeoutSeconds = requestObject.optInt("timeout", DEFAULT_TIMEOUT_SECONDS)
      .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    val followRedirects = requestObject.optBoolean("follow_redirects", true)
    val insecure = requestObject.optBoolean("insecure", false)

    var clientBuilder =
      baseClient.newBuilder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)

    if (insecure) {
      clientBuilder =
        clientBuilder
          .sslSocketFactory(unsafeSslSocketFactory, unsafeTrustManager)
          .hostnameVerifier { _, _ -> true }
    }

    val headers = requestObject.optJSONObject("headers") ?: JSONObject()
    val requestBody = buildRequestBody(requestObject, headers, method)
    val okRequest =
      Request.Builder()
        .url(url)
        .method(method, requestBody)
        .apply {
          headers.keys().forEach { key ->
            addHeaderValues(key, headers.get(key))
          }
        }.build()

    clientBuilder.build().newCall(okRequest).execute().use { response ->
      val outputFile = requestObject.optString("output_file", "")
      val responseBody =
        if (outputFile.isNotBlank()) {
          writeResponseBodyToFile(outputFile, response.body)
        } else if (requestObject.optBoolean("base64_response", false)) {
          java.util.Base64.getEncoder().encodeToString(response.body.bytes())
        } else {
          response.body.string()
        }

      return JSONObject().apply {
        put("id", requestId)
        put("status", response.code)
        put("body", responseBody)
        put("headers", responseHeadersToJson(response.headers))
        put("url", response.request.url.toString())
        put("ok", response.isSuccessful)
        put("error", JSONObject.NULL)
      }
    }
  }

  private fun Request.Builder.addHeaderValues(
    name: String,
    rawValue: Any?,
  ) {
    when (rawValue) {
      is JSONArray -> {
        for (index in 0 until rawValue.length()) {
          addHeader(name, rawValue.optString(index))
        }
      }
      JSONObject.NULL, null -> Unit
      else -> addHeader(name, rawValue.toString())
    }
  }

  private fun buildRequestBody(
    requestObject: JSONObject,
    headers: JSONObject,
    method: String,
  ): RequestBody? {
    val bodyAllowed = method !in METHODS_WITHOUT_BODY
    if (!bodyAllowed) return null

    val contentType = findHeader(headers, "content-type") ?: DEFAULT_CONTENT_TYPE
    val mediaType = contentType.toMediaTypeOrNull()
    val uploadFile = requestObject.optString("upload_file", "")

    if (uploadFile.isNotBlank()) {
      val file = File(uploadFile)
      if (!file.isFile) {
        throw FileNotFoundException("Upload file not found: $uploadFile")
      }
      return file.asRequestBody(mediaType)
    }

    if (!requestObject.has("body") && method !in METHODS_REQUIRING_BODY) {
      return null
    }

    val body = requestObject.optString("body", "")
    return if (requestObject.optBoolean("base64_body", false)) {
      java.util.Base64.getDecoder().decode(body).toRequestBody(mediaType)
    } else {
      body.toRequestBody(mediaType)
    }
  }

  private fun findHeader(
    headers: JSONObject,
    headerName: String,
  ): String? {
    val wanted = headerName.lowercase(Locale.US)
    headers.keys().forEach { key ->
      if (key.lowercase(Locale.US) == wanted) {
        val value = headers.get(key)
        return when (value) {
          is JSONArray -> value.optString(0).takeIf { it.isNotBlank() }
          JSONObject.NULL -> null
          else -> value.toString()
        }
      }
    }
    return null
  }

  private fun responseHeadersToJson(headers: okhttp3.Headers): JSONObject =
    JSONObject().apply {
      headers.forEach { (name, value) ->
        val existing = opt(name)
        when (existing) {
          null -> put(name, value)
          is JSONArray -> existing.put(value)
          else -> put(name, JSONArray().put(existing).put(value))
        }
      }
    }

  private fun writeResponseBodyToFile(
    outputFile: String,
    body: ResponseBody,
  ): String {
    val file = File(outputFile)
    file.parentFile?.mkdirs()
    body.byteStream().use { input ->
      file.outputStream().use { output ->
        input.copyTo(output)
      }
    }
    return file.absolutePath
  }

  private fun sendResponse(
    callback: CurlCallback,
    responseJson: JSONObject,
  ) {
    val payload = responseJson.toString()
    if (callback.targetScript.isNotBlank()) {
      runCatching {
        responseSender.sendTo(callback.targetScript, callback.messageName, payload)
      }.onSuccess {
        if (it) return
      }.onFailure { error ->
        Log.w(TAG, "script-message-to failed for ${callback.targetScript}", error)
      }
    }

    responseSender.send(callback.messageName, payload)
  }

  private fun errorResponse(
    requestId: String,
    message: String,
  ): JSONObject =
    JSONObject().apply {
      put("id", requestId)
      put("status", 0)
      put("body", "")
      put("headers", JSONObject())
      put("url", "")
      put("ok", false)
      put("error", message)
    }

  private fun normalizeJsonString(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("\"")) return trimmed

    val decoded = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
    return if (decoded is String) decoded.trim() else trimmed
  }

  private data class CurlCallback(
    val id: String = "",
    val messageName: String = "",
    val targetScript: String = "",
  ) {
    companion object {
      fun from(requestObject: JSONObject): CurlCallback =
        CurlCallback(
          id = requestObject.optString("id", ""),
          messageName = requestObject.optString("msg", ""),
          targetScript = requestObject.optString("target", ""),
        )
    }
  }

  private companion object {
    const val TAG = "CurlBridge"
    const val DEFAULT_TIMEOUT_SECONDS = 30
    const val MIN_TIMEOUT_SECONDS = 1
    const val MAX_TIMEOUT_SECONDS = 600
    const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    val METHODS_WITHOUT_BODY = setOf("GET", "HEAD")
    val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")

    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
  }
}

interface CurlResponseSender {
  fun sendTo(
    targetScript: String,
    messageName: String,
    payload: String,
  ): Boolean

  fun send(
    messageName: String,
    payload: String,
  )
}

private object MpvCurlResponseSender : CurlResponseSender {
  override fun sendTo(
    targetScript: String,
    messageName: String,
    payload: String,
  ): Boolean {
    MPVLib.command("script-message-to", targetScript, messageName, payload)
    return true
  }

  override fun send(
    messageName: String,
    payload: String,
  ) {
    MPVLib.command("script-message", messageName, payload)
  }
}
