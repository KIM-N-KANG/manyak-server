package com.knk.manyak.chat.client

/**
 * AI 채팅 턴 생성이 구조화된 오류로 실패했음을 나타낸다.
 *
 * AI 서버가 내려준 오류 [code]와 [message]를 담아, 백엔드는 이를 그대로
 * SSE error 이벤트로 relay한다. 백엔드 자체 오류는 이 예외를 쓰지 않고
 * 자체 코드로 응답한다.
 */
class ChatTurnAiException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
