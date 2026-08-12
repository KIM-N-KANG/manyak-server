package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.global.observability.AiTraceLink
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 채팅 AI 호출을 임의 지점에서 붙잡을 수 있는 페이크 배선. 동시성(경합) 통합 테스트가 "AI 호출이 진행 중인 동안
 * 다른 요청이 끼어드는" 순서를 결정적으로 만들 때 쓴다.
 *
 * 테스트 클래스마다 중첩 `@TestConfiguration`을 두지 않고 이 클래스를 `@Import`한다 — 중첩 설정은 클래스마다
 * Spring 컨텍스트를 하나씩 늘려 캐시 상한(32) 축출 사고로 이어졌다(KNK-686, `SpringContextBudgetGuardTests`).
 * 동작은 [gatedInput]·[nextChoices] 토글로 테스트마다 바꾸고, 끝나면 [reset]으로 원복한다.
 */
@TestConfiguration
class GatedChatTurnAiClientConfig {

    @Bean
    @Primary
    fun gatedChatTurnAiClient(): ChatTurnAiClient = object : ChatTurnAiClient {
        override fun streamTurn(
            request: ChatTurnAiRequest,
            traceLink: AiTraceLink,
            onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            if (gatedInput != null && request.userInput == gatedInput) {
                entered.countDown()
                // 무한 대기 금지 — 검증 대상이 깨져도 테스트가 매달리지 않고 실패로 끝나야 한다.
                gate.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            }
            onToken("생성 ")
            return ChatTurnAiResult(aiOutput = "생성된 본문입니다. ${request.userInput.take(8)}", choices = emptyList())
        }

        override fun generateChoices(
            request: ChatTurnAiRequest,
            aiOutput: String,
            traceLink: AiTraceLink,
        ): ChatChoicesResult = ChatChoicesResult(choices = nextChoices)
    }

    companion object {
        const val AWAIT_SECONDS = 20L

        /** 이 입력의 턴 호출만 [gate]에서 붙잡는다. null이면 아무것도 붙잡지 않는다. */
        @Volatile
        @JvmStatic
        var gatedInput: String? = null

        /** 선택지 생성 트리거가 돌려줄 집합. */
        @Volatile
        @JvmStatic
        var nextChoices: List<String> = emptyList()

        /** 붙잡힌 AI 호출을 푸는 문. */
        @Volatile
        @JvmStatic
        var gate = CountDownLatch(1)

        /** 붙잡힌 AI 호출 구간에 실제로 진입했음을 알리는 신호. */
        @Volatile
        @JvmStatic
        var entered = CountDownLatch(1)

        @JvmStatic
        fun reset() {
            gatedInput = null
            nextChoices = emptyList()
            gate = CountDownLatch(1)
            entered = CountDownLatch(1)
        }
    }
}
