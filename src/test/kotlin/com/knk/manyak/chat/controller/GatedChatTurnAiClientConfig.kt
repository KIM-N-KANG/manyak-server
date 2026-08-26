package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatCharacterImageEvent
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
 * 채팅 AI 호출 공용 페이크 배선. 호출을 임의 지점에서 붙잡아(동시성 테스트) 순서를 결정적으로 만들고,
 * 보낸 요청을 캡처하며([lastRequest]), 스트리밍 중 인물 이미지 이벤트를 발행할 수 있다([nextCharacterImage]).
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
            onCharacterImage: (ChatCharacterImageEvent) -> Unit,
            onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            lastRequest = request
            if (gatedInput != null && request.userInput == gatedInput) {
                entered.countDown()
                // 무한 대기 금지 — 검증 대상이 깨져도 테스트가 매달리지 않고 실패로 끝나야 한다.
                gate.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            }
            onToken("생성 ")
            // 인물 이미지 이벤트는 본문 토큰 사이에 끼어든다(스펙 §4-3-3의 이벤트 순서).
            if (interruptBeforeCharacterImage) {
                // 클라이언트가 끊겨 워커가 인터럽트된 상황 재현. 이벤트 발행 뒤 플래그를 지워
                // 이후 저장·완료 경로는 정상으로 흐르게 한다(검증 대상은 이미지 이벤트 skip 하나다).
                Thread.currentThread().interrupt()
                nextCharacterImage?.let(onCharacterImage)
                Thread.interrupted()
            } else {
                nextCharacterImage?.let(onCharacterImage)
            }
            onToken("된 본문입니다.")
            return ChatTurnAiResult(aiOutput = "생성된 본문입니다. ${request.userInput.take(8)}", choices = emptyList())
        }

        override fun generateChoices(
            request: ChatTurnAiRequest,
            aiOutput: String,
            traceLink: AiTraceLink,
        ): ChatChoicesResult {
            lastRequest = request
            return ChatChoicesResult(choices = nextChoices)
        }
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

        /** 마지막으로 AI에 보낸 요청. 요청 조립(인물 이미지 매핑 등)을 검증할 때 읽는다. */
        @Volatile
        @JvmStatic
        var lastRequest: ChatTurnAiRequest? = null

        /** 값이 있으면 스트리밍 도중 이 인물 이미지 이벤트를 발행한다. null이면 발행하지 않는다(AI 구버전과 같은 모습). */
        @Volatile
        @JvmStatic
        var nextCharacterImage: ChatCharacterImageEvent? = null

        /** true면 인물 이미지 이벤트를 발행하는 순간에만 워커 스레드를 인터럽트한다(연결 끊김 재현). */
        @Volatile
        @JvmStatic
        var interruptBeforeCharacterImage: Boolean = false

        @JvmStatic
        fun reset() {
            gatedInput = null
            nextChoices = emptyList()
            lastRequest = null
            nextCharacterImage = null
            interruptBeforeCharacterImage = false
            gate = CountDownLatch(1)
            entered = CountDownLatch(1)
        }
    }
}
