package com.knk.manyak.chat.client

import io.netty.channel.ChannelOption
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/**
 * 실연동 AI 클라이언트용 인프라 빈 설정.
 *
 * [RestChatTurnAiClient]가 사용할 [WebClient]를 `manyak.ai.base-url`에 고정해 등록한다.
 * 스텁을 쓰는 프로필(`manyak.ai.chat.stub=true`)에서는 WebClient를 만들지 않도록
 * 실연동 클라이언트와 동일한 조건으로 게이팅한다.
 *
 * AI 서버가 닿지 않을 때 무한정 매달리지 않도록 connect 타임아웃을 둔다. 응답(read) 타임아웃은
 * 두지 않는다. SSE는 장시간 연결이라 고정 read 타임아웃이 정상 생성을 끊어버리기 때문이다.
 * 대신 멈춘 스트림은 [RestChatTurnAiClient]의 이벤트 간 idle 타임아웃이 끊는다.
 */
@Configuration
class ChatAiClientConfig {

    @Bean
    @ConditionalOnProperty(name = ["manyak.ai.chat.stub"], havingValue = "false", matchIfMissing = true)
    fun chatTurnWebClient(
        @Value("\${manyak.ai.base-url}") baseUrl: String,
        @Value("\${manyak.ai.chat.connect-timeout:5s}") connectTimeout: Duration,
    ): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
