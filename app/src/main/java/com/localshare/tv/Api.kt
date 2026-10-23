package com.localshare.tv

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** [url] is set only for files streamed from a phone's browser page; files from the LocalShare app are fetched through [Api]. */
data class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val kind: String, val url: String = "")

/** Talks to the LocalShare server running on the phone. One instance per connected phone. */
class Api(val base: String, private val password: String, private val deviceId: String) {
    @Volatile private var cookie = ""

    /** Also used by the video player and the image loader, so streams carry the sign-in cookie. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { call(it, 0) }
        .build()

    /** Adds the sign-in cookie, signs in again if the phone says the session ended, and retries a busy thumbnail. */
    private fun call(chain: Interceptor.Chain, tries: Int): Response {
        val req = chain.request()
        val sent = cookie
        val r = chain.proceed(if (sent.isEmpty()) req else req.newBuilder().header("Cookie", sent).build())
        val path = req.url.encodedPath
        if (r.code == 401 && path != "/api/login" && tries == 0) {
            r.close()
            synchronized(this) { if (cookie == sent) try { login() } catch (e: ApiException) { } }
            return call(chain, 1)
        }
        if (r.code == 503 && path == "/api/thumb" && tries < 3) {
            r.close()
            try { Thread.sleep(350L * (tries + 1)) } catch (e: InterruptedException) { }
            return call(chain, tries + 1)
        }
        return r
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun fileUrl(path: String) = "$base/api/file?path=${enc(path)}"
    fun thumbUrl(path: String) = "$base/api/thumb?path=${enc(path)}&w=480"

    fun login() {
        val body = FormBody.Builder().add("name", "LocalShare TV").add("password", password).add("device", deviceId).build()
        try {
            client.newCall(Request.Builder().url("$base/api/login").post(body).build()).execute().use { r ->
                if (r.code == 200) {
                    cookie = r.headers("Set-Cookie").firstOrNull { it.startsWith("ls_token=") }?.substringBefore(';') ?: ""
                    if (cookie.isEmpty()) throw ApiException("The phone did not sign us in")
                    return
                }
                val err = try { JSONObject(r.body?.string() ?: "{}") } catch (e: Exception) { JSONObject() }
                throw ApiException(
                    when (r.code) {
                        401 -> "Wrong password"
                        403 -> "This TV has been blocked on the phone"
                        429 -> if (err.has("retry")) "Too many tries. Wait ${err.optInt("retry")} seconds." else "The phone is full. Disconnect another device."
                        else -> err.optString("error", "Could not connect")
                    }, r.code,
                )
            }
        } catch (e: java.io.IOException) {
            throw ApiException("Cannot reach the phone. Is sharing on and on the same Wi-Fi?")
        }
    }

    fun list(path: String, retry: Boolean = true): List<Entry> {
        val req = Request.Builder().url("$base/api/list?path=${enc(path)}").build()
        try {
            client.newCall(req).execute().use { r ->
                if (r.code == 401 && retry) { login(); return list(path, false) }
                if (!r.isSuccessful) throw ApiException(if (r.code == 404) "That folder is gone" else "Could not open the folder", r.code)
                val arr = JSONObject(r.body?.string() ?: "{}").getJSONArray("entries")
                return (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    val n = o.getString("n")
                    Entry(n, if (path.isEmpty()) n else "$path/$n", o.getInt("d") == 1, o.optLong("s"), o.optString("k"))
                }
            }
        } catch (e: java.io.IOException) {
            throw ApiException("Lost contact with the phone")
        }
    }
}
