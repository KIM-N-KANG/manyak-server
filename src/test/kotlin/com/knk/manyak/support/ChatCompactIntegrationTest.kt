package com.knk.manyak.support

import com.knk.manyak.chat.service.ChatCompactTestConfig
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * 채팅 압축(compact) 배선을 직접 검증하는 조합(C2, KNK-2053).
 *
 * [ChatCompactTestConfig]가 턴 AI·압축 AI·압축 executor·구조화 로거를 함께 바꾸므로 [ScriptedAiIntegrationTest]와 섞을 수 없다.
 * 압축 켜짐/꺼짐은 컨텍스트를 가르지 않도록 속성 대신 공용 스파이 `ChatCompactSettings.enabled`를 테스트마다 스텁한다.
 * 동시성 테스트가 행 잠금을 기다리므로 잠금 대기를 늘린 전용 H2를 쓴다.
 */
@Import(ChatCompactTestConfig::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:compact;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=10000",
    ],
)
abstract class ChatCompactIntegrationTest : IntegrationTest()
