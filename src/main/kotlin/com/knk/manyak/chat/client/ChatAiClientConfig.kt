package com.knk.manyak.chat.client

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * 실연동 AI 클라이언트용 인프라 빈 설정.
 *
 * [RestChatTurnAiClient]가 사용할 [WebClient]를 `manyak.ai.base-url`에 고정해 등록한다.
 * 스텁을 쓰는 프로필(`manyak.ai.chat.stub=true`)에서는 WebClient를 만들지 않도록
 * 실연동 클라이언트와 동일한 조건으로 게이팅한다.
 */
@Configuration
class ChatAiClientConfig {

    @Bean
    @ConditionalOnProperty(name = ["manyak.ai.chat.stub"], havingValue = "false", matchIfMissing = true)
    fun chatTurnWebClient(
        @Value("\${manyak.ai.base-url}") baseUrl: String,
    ): WebClient = WebClient.builder().baseUrl(baseUrl).build()
}
