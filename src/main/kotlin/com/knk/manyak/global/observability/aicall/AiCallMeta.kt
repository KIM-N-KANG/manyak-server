package com.knk.manyak.global.observability.aicall

/**
 * AI 응답에 실려 오는 호출 메타.
 *
 * story(storyline/compile, snake_case)와 chat(completed 이벤트, camelCase)은 와이어 표기가 다르지만
 * 의미는 같다. 각 client의 수신 DTO를 서비스 계층에서 이 공통 형태로 정규화해 [AiCallRecorder]에 넘긴다.
 *
 * promptVersions는 AI가 보낸 키→버전 맵(예: storylines={"STORYLINES":2}, chat={"SAFETY":1,...})을
 * 그대로 보관한다. feature 차원은 ai_call_logs.feature 컬럼이 이미 구분하므로 레이어 키를 변환하지 않는다.
 * 모든 필드는 AI 미구현·부분 응답을 견디도록 nullable이다.
 */
data class AiCallMeta(
    val model: String? = null,
    val provider: String? = null,
    val inputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val retryCount: Int? = null,
    val promptVersions: Map<String, Int>? = null,
)
