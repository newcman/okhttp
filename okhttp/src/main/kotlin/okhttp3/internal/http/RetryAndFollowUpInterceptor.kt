/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT
import java.net.HttpURLConnection.HTTP_MOVED_PERM
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_PROXY_AUTH
import java.net.HttpURLConnection.HTTP_SEE_OTHER
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.net.HttpURLConnection.HTTP_UNAVAILABLE
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RouteException
import okhttp3.internal.http.StatusLine.Companion.HTTP_MISDIRECTED_REQUEST
import okhttp3.internal.http.StatusLine.Companion.HTTP_PERM_REDIRECT
import okhttp3.internal.http.StatusLine.Companion.HTTP_TEMP_REDIRECT
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.withSuppressed

/**
 * 重试/重定向
 * 交出拦截器之前：判断用户是否取消了请求
 * 获得结果之后：根据响应码是否现有一套重定向
 * 满足条件会重启执行所有拦截器
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * [IOException] if the call was canceled.
 */
class RetryAndFollowUpInterceptor(private val client: OkHttpClient) : Interceptor {

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    var request = chain.request
    val call = realChain.call
    var followUpCount = 0
    var priorResponse: Response? = null
    var newExchangeFinder = true
    var recoveredFailures = listOf<IOException>()
    while (true) { // 重试
      call.enterNetworkInterceptorExchange(request, newExchangeFinder)

      var response: Response
      var closeActiveExchange = true
      try {
        if (call.isCanceled()) {
          throw IOException("Canceled")
        }

        try {
          response = realChain.proceed(request) //  主要是对于响应做处理
          newExchangeFinder = true
        } catch (e: RouteException) {
          // 路由异常，连接未成功，请求还没发出去
          // The attempt to connect via a route failed. The request will not have been sent.
          if (!recover(e.lastConnectException, call, request, requestSendStarted = false)) {
            throw e.firstConnectException.withSuppressed(recoveredFailures)
          } else {
            recoveredFailures += e.firstConnectException
          }
          newExchangeFinder = false
          continue // 重试
        } catch (e: IOException) {
          // An attempt to communicate with a server failed. The request may have been sent.
          if (!recover(e, call, request, requestSendStarted = e !is ConnectionShutdownException)) {
            throw e.withSuppressed(recoveredFailures)
          } else {
            recoveredFailures += e
          }
          newExchangeFinder = false
          continue // 重试
        }

        // Attach the prior response if it exists. Such responses never have a body.
        // 如果进过重试/重定向才成功的，则在本次响应中记录上次响应的情况
        if (priorResponse != null) {
          response = response.newBuilder()
              .priorResponse(priorResponse.newBuilder()
                  .body(null)
                  .build())
              .build()
        }

        val exchange = call.interceptorScopedExchange
        // 处理3xx和4xx的一些状态码：301、302重定向
        val followUp = followUpRequest(response, exchange)

        if (followUp == null) { // 无需重定向
          if (exchange != null && exchange.isDuplex) {
            call.timeoutEarlyExit()
          }
          closeActiveExchange = false
          return response
        }

        val followUpBody = followUp.body
        if (followUpBody != null && followUpBody.isOneShot()) {
          closeActiveExchange = false
          return response
        }

        response.body?.closeQuietly()

        if (++followUpCount > MAX_FOLLOW_UPS) { // 重定向次数不能超过一定数量
          throw ProtocolException("Too many follow-up requests: $followUpCount")
        }

        request = followUp
        priorResponse = response
      } finally {
        call.exitNetworkInterceptorExchange(closeActiveExchange)
      }
    }
  }

  /**
   * 去重试
   * Report and attempt to recover from a failure to communicate with a server. Returns true if
   * `e` is recoverable, or false if the failure is permanent. Requests with a body can only
   * be recovered if the body is buffered or if the failure occurred before the request has been
   * sent.
   */
  private fun recover(
    e: IOException,
    call: RealCall,
    userRequest: Request,
    requestSendStarted: Boolean
  ): Boolean {
    // The application layer has forbidden retries.
    // 全局配置是否可以重试
    if (!client.retryOnConnectionFailure) return false

    // We can't send the request body again.
    // 单次请求是否允许重试
    if (requestSendStarted && requestIsOneShot(e, userRequest)) return false

    // This exception is fatal.
    // 判断是否属于重试的异常
    if (!isRecoverable(e, requestSendStarted)) return false

    // No more routes to attempt.
    // 是否存在更多的路由
    if (!call.retryAfterFailure()) return false

    // For failure recovery, use the same route selector with a new connection.
    return true
  }
  // 单次请求是否允许重试
  private fun requestIsOneShot(e: IOException, userRequest: Request): Boolean {
    val requestBody = userRequest.body
    return (requestBody != null && requestBody.isOneShot()) ||
        e is FileNotFoundException
  }
  // 是否可以恢复
  private fun isRecoverable(e: IOException, requestSendStarted: Boolean): Boolean {
    // If there was a protocol problem, don't recover.
    // 协议异常 如204但是返回了内容，参考CallServerInterceptor
    if (e is ProtocolException) {
      return false
    }

    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    if (e is InterruptedIOException) {
      return e is SocketTimeoutException && !requestSendStarted // sockettimout可以重试
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (e is SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.cause is CertificateException) { // 证书异常，不重试
        return false
      }
    }
    if (e is SSLPeerUnverifiedException) { // 证书校验失败 不匹配、过期 ：不重试
      // e.g. a certificate pinning error.
      return false
    }
    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true
  }

  /**
   * 重定向
   * Figures out the HTTP request to make in response to receiving [userResponse]. This will
   * either add authentication headers, follow redirects or handle a client request timeout. If a
   * follow-up is either unnecessary or not applicable, this returns null.
   */
  @Throws(IOException::class)
  private fun followUpRequest(userResponse: Response, exchange: Exchange?): Request? {
    val route = exchange?.connection?.route()
    val responseCode = userResponse.code

    val method = userResponse.request.method
    when (responseCode) {
      HTTP_PROXY_AUTH -> { // 设置代理 407
        val selectedProxy = route!!.proxy
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy")
        }
        return client.proxyAuthenticator.authenticate(route, userResponse) // 鉴权接口，设置用户名和密码
      }
      // 401 无权限
      HTTP_UNAUTHORIZED -> return client.authenticator.authenticate(route, userResponse) //
      //  重定向
      HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT, HTTP_MULT_CHOICE, HTTP_MOVED_PERM, HTTP_MOVED_TEMP, HTTP_SEE_OTHER -> {
        return buildRedirectRequest(userResponse, method)
      }

      HTTP_CLIENT_TIMEOUT -> { // 408客户端超时
        // 408's are rare in practice, but some servers like HAProxy use this response code. The
        // spec says that we may repeat the request without modifications. Modern browsers also
        // repeat the request (even non-idempotent ones.)
        if (!client.retryOnConnectionFailure) {
          // The application layer has directed us not to retry the request.
          return null
        }

        val requestBody = userResponse.request.body
        if (requestBody != null && requestBody.isOneShot()) {
          return null
        }
        val priorResponse = userResponse.priorResponse
        if (priorResponse != null && priorResponse.code == HTTP_CLIENT_TIMEOUT) {
          // We attempted to retry and got another timeout. Give up.
          return null
        }

        if (retryAfter(userResponse, 0) > 0) {
          return null
        }

        return userResponse.request // 直接返回
      }

      HTTP_UNAVAILABLE -> { // 503服务不可用
        val priorResponse = userResponse.priorResponse
        if (priorResponse != null && priorResponse.code == HTTP_UNAVAILABLE) {
          // We attempted to retry and got another timeout. Give up.
          return null
        }

        if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) { // 马上重试
          // specifically received an instruction to retry without delay
          return userResponse.request
        }

        return null
      }

      HTTP_MISDIRECTED_REQUEST -> {
        // OkHttp can coalesce HTTP/2 connections even if the domain names are different. See
        // RealConnection.isEligible(). If we attempted this and the server returned HTTP 421, then
        // we can retry on a different connection.
        val requestBody = userResponse.request.body
        if (requestBody != null && requestBody.isOneShot()) {
          return null
        }

        if (exchange == null || !exchange.isCoalescedConnection) {
          return null
        }

        exchange.connection.noCoalescedConnections()
        return userResponse.request
      }

      else -> return null
    }
  }

  private fun buildRedirectRequest(userResponse: Response, method: String): Request? {
    // Does the client allow redirects?
    if (!client.followRedirects) return null

    val location = userResponse.header("Location") ?: return null
    // Don't follow redirects to unsupported protocols.
    val url = userResponse.request.url.resolve(location) ?: return null

    // If configured, don't follow redirects between SSL and non-SSL.
    val sameScheme = url.scheme == userResponse.request.url.scheme
    if (!sameScheme && !client.followSslRedirects) return null

    // Most redirects don't include a request body.
    val requestBuilder = userResponse.request.newBuilder()
    if (HttpMethod.permitsRequestBody(method)) {
      val responseCode = userResponse.code
      val maintainBody = HttpMethod.redirectsWithBody(method) ||
          responseCode == HTTP_PERM_REDIRECT ||
          responseCode == HTTP_TEMP_REDIRECT
      if (HttpMethod.redirectsToGet(method) && responseCode != HTTP_PERM_REDIRECT && responseCode != HTTP_TEMP_REDIRECT) {
        requestBuilder.method("GET", null)
      } else {
        val requestBody = if (maintainBody) userResponse.request.body else null
        requestBuilder.method(method, requestBody)
      }
      if (!maintainBody) {
        requestBuilder.removeHeader("Transfer-Encoding")
        requestBuilder.removeHeader("Content-Length")
        requestBuilder.removeHeader("Content-Type")
      }
    }

    // When redirecting across hosts, drop all authentication headers. This
    // is potentially annoying to the application layer since they have no
    // way to retain them.
    if (!userResponse.request.url.canReuseConnectionFor(url)) {
      requestBuilder.removeHeader("Authorization")
    }

    return requestBuilder.url(url).build()
  }

  private fun retryAfter(userResponse: Response, defaultDelay: Int): Int {
    val header = userResponse.header("Retry-After") ?: return defaultDelay // 过段时间再过来请求

    // https://tools.ietf.org/html/rfc7231#section-7.1.3
    // currently ignores a HTTP-date, and assumes any non int 0 is a delay
    if (header.matches("\\d+".toRegex())) {
      return Integer.valueOf(header)
    }
    return Integer.MAX_VALUE
  }

  companion object {
    /**
     * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
     * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
     */
    private const val MAX_FOLLOW_UPS = 20
  }
}
