package com.knk.manyak.support

import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.HttpResponse
import org.apache.hc.core5.http.protocol.HttpContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.resttestclient.autoconfigure.RestTestClientBuilderCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.http.server.LocalTestWebServer
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 테스트 HTTP 클라이언트가 503·429 응답을 다시 보내지 않게 한다(KNK-2058).
 *
 * RestTestClient는 클래스패스의 HttpComponents 5(firebase-admin·AWS SDK가 끌어옴)를 감지해 쓰는데, 그 기본 재시도
 * 전략은 503·429 응답을 1초 뒤 한 번 더 보낸다. 503을 기대하는 테스트마다 1초씩 기다리고 서버를 두 번 호출했다.
 * 응답 기반 재시도만 끄고 IOException 재시도(끊긴 커넥션 재전송)는 기본값 그대로 둔다.
 * Boot의 RestTestClient 빈은 `@ConditionalOnMissingBean`이라 이 빈이 대신하며, Boot 커스터마이저는 그대로 적용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class NoResponseRetryRestTestClientConfig {
    @Bean
    fun restTestClient(context: ApplicationContext, customizers: ObjectProvider<RestTestClientBuilderCustomizer>): RestTestClient {
        val noResponseRetry = object : DefaultHttpRequestRetryStrategy() {
            override fun retryRequest(response: HttpResponse, execCount: Int, context: HttpContext) = false
        }
        val http = HttpClients.custom().useSystemProperties().setRetryStrategy(noResponseRetry).build()
        val builder = RestTestClient.bindToServer(HttpComponentsClientHttpRequestFactory(http))
            .uriBuilderFactory(LocalTestWebServer.obtain(context).uriBuilderFactory())
        customizers.orderedStream().forEach { it.customize(builder) }
        return builder.build()
    }
}
