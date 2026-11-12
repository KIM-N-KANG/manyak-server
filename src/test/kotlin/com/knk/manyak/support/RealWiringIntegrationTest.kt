package com.knk.manyak.support

import com.knk.manyak.credit.controller.CreditOrderPaymentTestInitializer
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource

/**
 * 스텁 대신 실제 배선을 검증하는 조합(W, KNK-2053).
 *
 * - AI 스텁을 끄고 실연동 클라이언트(`RestChatTurnAiClient`·`RestStoryAiClient`)가 주입되는지 본다. 호출은 하지 않는다.
 * - 결제창 링크를 테스트 값으로 바꾸는 [CreditOrderPaymentTestInitializer]를 쓴다. 기본 조합에는 실제 yml 링크를 검증하는
 *   테스트가 있어 둘 수 없다. 이 조합의 다른 테스트는 결제창 링크를 쓰지 않는다.
 */
@ContextConfiguration(initializers = [CreditOrderPaymentTestInitializer::class])
@TestPropertySource(
    properties = [
        "manyak.ai.chat.stub=false",
        "manyak.ai.story.stub=false",
        "manyak.ai.base-url=http://localhost:8000",
    ],
)
abstract class RealWiringIntegrationTest : IntegrationTest()
