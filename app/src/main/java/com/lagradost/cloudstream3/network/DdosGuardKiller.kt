package com.lagradost.cloudstream3.network

import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * @param alwaysBypass will pre-emptively fetch ddos guard cookies if true.
 * If false it will only try to get cookies when a request returns 403
 * */
// As seen in https://github.com/anime-dl/anime-downloader/blob/master/anime_downloader/sites/erairaws.py
@AnyThread
class DdosGuardKiller(private val alwaysBypass: Boolean) : Interceptor {
    // Read and written from several OkHttp dispatcher threads, so it has to be concurrent.
    val savedCookiesMap: MutableMap<String, Map<String, String>> = java.util.concurrent.ConcurrentHashMap()

    @Volatile
    private var ddosBypassPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        if (alwaysBypass) return@runBlocking bypassDdosGuard(request)

        val response = chain.proceed(request)
        if (response.code != 403) return@runBlocking response

        // The 403 response has to be closed before a second request is issued on the same
        // chain, otherwise the connection is never returned to the pool and leaks.
        response.close()
        bypassDdosGuard(request)
    }

    private suspend fun bypassDdosGuard(request: Request): Response {
        ddosBypassPath = ddosBypassPath ?: Regex("'(.*?)'").find(
            app.get(
                "https://check.ddos-guard.net/check.js"
            ).text
        )?.groupValues?.get(1)

        val cookies =
            savedCookiesMap[request.url.host]
            // If no cookies are found fetch and save em.
                ?: (request.url.scheme + "://" + request.url.host + (ddosBypassPath ?: "")).let {
                    // Somehow app.get fails
                    app.get(it).cookies.also { cookies ->
                        savedCookiesMap[request.url.host] = cookies
                    }
                }

        val headers = getHeaders(request.headers.toMap(), cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }
}