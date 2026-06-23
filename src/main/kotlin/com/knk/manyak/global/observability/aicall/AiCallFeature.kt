package com.knk.manyak.global.observability.aicall

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * ai_call_logs.feature 차원.
 *
 * CloudWatch 이벤트 이름(`ai_response_saved` 등)과 분석 일관성을 맞추기 위해
 * DB에는 소문자 snake_case 문자열로 저장한다.
 *
 * SUGGESTION_GENERATION은 현재 독립 AI 호출이 없어(추천 입력이 storyline/compile 응답에 함께 옴)
 * 적재 지점이 없지만, 추천이 별도 호출로 분리될 때를 대비해 차원만 미리 정의해 둔다.
 */
enum class AiCallFeature(val value: String) {
    STORYLINE_GENERATION("storyline_generation"),
    STORY_COMPLETION("story_completion"),
    CHAT_RESPONSE("chat_response"),
    SUGGESTION_GENERATION("suggestion_generation"),
    ;

    companion object {
        fun from(value: String): AiCallFeature =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown AiCallFeature value: $value")
    }
}

/**
 * AiCallFeature를 DB의 소문자 snake_case 문자열로 변환한다. `@Convert`로 명시 적용한다(autoApply 아님).
 */
@Converter
class AiCallFeatureConverter : AttributeConverter<AiCallFeature, String> {
    override fun convertToDatabaseColumn(attribute: AiCallFeature?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): AiCallFeature? =
        dbData?.let(AiCallFeature::from)
}
