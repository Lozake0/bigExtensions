package eu.kanade.tachiyomi.extension.pt.lycantoons

import keiyoushi.utils.WebViewScope
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.runWebViewBlocking
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

// LycanToons' Cloudflare blocks the reader endpoints and the CDN for anything without a browser's network
// fingerprint, headers don't matter. Those requests run as same-origin fetch() calls from a blank WebView
// document on the target origin, cookieless like the site's own reader. Each call gets a fresh WebView:
// a pooled one gets frozen by Chromium once it sits detached on about:blank and its fetches never settle.
class BrowserFetch {

    suspend fun text(origin: String, path: String, reload: Boolean = false): String {
        val response = runWebView(timeout = TIMEOUT) { fetchFrom(origin, path, reload, binary = false) }.parseAs<BrowserResponseDto>()

        return response.ensureSuccess().body
    }

    val imageInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host !in BROWSER_HOSTS) return@Interceptor chain.proceed(request)

        val origin = "${request.url.scheme}://${request.url.host}/"
        val result = runWebViewBlocking(chain.call(), timeout = TIMEOUT) {
            fetchFrom(origin, request.url.toString(), reload = false, binary = true)
        }.parseAs<BrowserResponseDto>()
        if (result.status == 0) throw IOException("Falha de rede: ${result.body}")

        val bytes = if (result.status == 200) Base64.getDecoder().decode(result.body) else ByteArray(0)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(result.status)
            .message(if (result.status == 200) "OK" else "HTTP ${result.status}")
            .body(bytes.toResponseBody(result.type?.toMediaTypeOrNull()))
            .build()
    }

    private fun WebViewScope<String>.fetchFrom(origin: String, url: String, reload: Boolean, binary: Boolean) {
        blockImages = true
        jsBridge(BRIDGE) { resolve(it) }
        onPageFinished { evaluateJs(fetchScript(url, reload, binary)) }
        loadData(origin, "")
    }

    private fun fetchScript(url: String, reload: Boolean, binary: Boolean) = """
        (async () => {
            const send = (status, type, body) => $BRIDGE.post(JSON.stringify({ status, type, body }));
            try {
                const r = await fetch(${url.toJsonString()}, { credentials: "omit", cache: "${if (reload) "reload" else "default"}" });
                const type = r.headers.get("content-type");
                if (!$binary || !r.ok) return send(r.status, type, await r.text());
                const blob = await r.blob();
                const data = await new Promise((ok, fail) => {
                    const reader = new FileReader();
                    reader.onload = () => ok(reader.result.slice(reader.result.indexOf(",") + 1));
                    reader.onerror = () => fail(reader.error);
                    reader.readAsDataURL(blob);
                });
                send(r.status, type, blob.size ? data : "");
            } catch (e) {
                send(0, null, String(e));
            }
        })();
    """.trimIndent()

    private fun BrowserResponseDto.ensureSuccess(): BrowserResponseDto = when (status) {
        200 -> this
        0 -> throw IOException("Falha de rede: $body")
        403 -> throw IOException("Bloqueado pela LycanToons (HTTP 403)")
        else -> throw IOException("HTTP $status")
    }

    companion object {
        private const val BRIDGE = "LycanBridge"
        private val TIMEOUT = 60.seconds
        private val BROWSER_HOSTS = setOf("cdn.lycantoons.com")
    }
}

@Serializable
class BrowserResponseDto(
    val status: Int,
    val type: String? = null,
    val body: String = "",
)
