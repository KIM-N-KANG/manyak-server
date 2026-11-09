package com.knk.manyak.chat.client

import com.knk.manyak.support.RealWiringIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * `manyak.ai.chat.stub=false`에서 실연동 빈([RestChatTurnAiClient])이 등록되고
 * 스텁이 배제되는지 검증한다. 스텁 활성(stub=true) 경로는 기존 스트림 통합 테스트가 덮는다.
 */
class ChatTurnAiClientWiringIntegrationTests : RealWiringIntegrationTest() {

    @Autowired
    private lateinit var chatTurnAiClient: ChatTurnAiClient

    @Test
    fun `stub이 false면 RestChatTurnAiClient가 주입된다`() {
        assertThat(chatTurnAiClient).isInstanceOf(RestChatTurnAiClient::class.java)
    }
}
