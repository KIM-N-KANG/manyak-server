package com.knk.manyak.chat.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * `manyak.ai.chat.stub=false`에서 실연동 빈([RestChatTurnAiClient])이 등록되고
 * 스텁이 배제되는지 검증한다. 스텁 활성(stub=true) 경로는 기존 스트림 통합 테스트가 덮는다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "manyak.ai.chat.stub=false",
        "manyak.ai.base-url=http://localhost:8000",
    ],
)
class ChatTurnAiClientWiringIntegrationTests {

    @Autowired
    private lateinit var chatTurnAiClient: ChatTurnAiClient

    @Test
    fun `stub이 false면 RestChatTurnAiClient가 주입된다`() {
        assertThat(chatTurnAiClient).isInstanceOf(RestChatTurnAiClient::class.java)
    }
}
