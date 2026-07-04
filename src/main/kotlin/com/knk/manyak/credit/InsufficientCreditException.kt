package com.knk.manyak.credit

/**
 * 크레딧 잔액이 소모액보다 적을 때 던진다. 소모 대상 엔드포인트가 이를 잡아 동기 `402 PAYMENT_REQUIRED`로 변환한다(스펙 §4-3-7).
 *
 * HTTP에 결합하지 않는 도메인 예외로 둔다 — 채팅 턴은 SSE 스트림을 열기 전에 동기 402를 반환해야 하므로, 변환 시점을 호출부가 결정한다.
 */
class InsufficientCreditException(
    val userId: Long,
    val required: Long,
    val balance: Long,
) : RuntimeException("크레딧이 부족합니다. required=$required, balance=$balance")
