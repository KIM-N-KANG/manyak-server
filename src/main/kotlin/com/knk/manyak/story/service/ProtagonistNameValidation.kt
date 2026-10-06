package com.knk.manyak.story.service

import com.knk.manyak.global.error.ApiErrorDetail
import com.knk.manyak.global.error.CodedResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

fun normalizeProtagonistName(raw: String?): String? {
    val name = raw?.trim() ?: return null
    if (name.length !in 1..30) throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "기본 주인공 이름이 올바르지 않습니다.", details = listOf(ApiErrorDetail("protagonistName", "앞뒤 공백을 제외하고 1~30자여야 합니다.")))
    return name
}

/** 대상 필드만 검사하며 주변 인물 이름, 장르, 이미지 URL은 포함하지 않는다. */
@Component
class ProtagonistNameValidation(private val mapper: ObjectMapper) {
    fun validate(value: Any) {
        val form = if (value is JsonNode) value else mapper.valueToTree<JsonNode>(value)
        val name = form.path("protagonistName").takeUnless { it.isNull || it.isMissingNode }?.asText()
        val normalized = normalizeProtagonistName(name)
        if (normalized == null && texts(form).any(UsernameTokenRenderer::containsToken)) {
            throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "이름 토큰을 사용하려면 기본 주인공 이름이 필요합니다.", details = listOf(ApiErrorDetail("protagonistName", "이름 토큰을 사용하려면 필수입니다.")))
        }
    }

    private fun texts(form: JsonNode): List<String> = buildList {
        fun fields(node: JsonNode, vararg keys: String) { keys.forEach { key -> node.path(key).takeIf { it.isString }?.let { add(it.asText()) } } }
        fields(form, "title", "oneLineIntro", "description")
        fields(form.path("storySettings"), "worldSetting", "characterSetting", "userRoleSetting", "ruleSetting")
        form.path("startSettings").forEach { start ->
            fields(start, "name", "startSituation", "prologue")
            start.path("suggestedInputs").forEach { if (it.isString) add(it.asText()) }
            start.path("endings").forEach { ending -> fields(ending, "name", "epilogue"); fields(ending.path("requirement"), "achievementCondition") }
        }
        form.path("mainEvents").forEach { fields(it, "name", "description", "keySentence") }
        form.path("characters").forEach { fields(it, "description") }
    }
}
