package com.knk.manyak.global.observability.aicall

/**
 * AI가 계약대로 응답했지만 내용을 거절한 예외에 붙이는 표식(KNK-2068). [AiCallRecorder]는 이 예외를 실패가 아니라
 * SUCCEEDED로 기록하고 예외는 그대로 전파한다. global이 도메인 예외를 직접 알지 않도록 도메인 예외가 이 표식을 구현한다.
 */
interface AiCallContractRejection
