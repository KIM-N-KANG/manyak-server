package com.knk.manyak.global.observability.aicall

/**
 * AI 호출의 생애주기 상태.
 *
 * 호출 직전 STARTED로 적재한 뒤, 결과에 따라 SUCCEEDED 또는 FAILED로 전이한다.
 * 전이가 일어나지 않고 STARTED로 남은 행은 적재 직후 프로세스가 죽은 미완료 호출을 뜻한다.
 */
enum class AiCallStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
}
