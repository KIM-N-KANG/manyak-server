package com.knk.manyak.story.service

import com.knk.manyak.story.client.AiStoryCompileResponse
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** 옛 AI의 이름 누락은 수용하되 토큰을 표시할 수 없는 새 응답은 저장 전에 거부한다. */
internal fun validateCompileProtagonist(response: AiStoryCompileResponse): String? {
    try {
        val name = normalizeProtagonistName(response.storySettings.protagonistName)
        val texts = buildList<String> {
            with(response.stories) { add(title); add(oneLineIntro); add(description) }
            with(response.storySettings) { add(worldSetting); add(characterSetting); add(userRoleSetting); add(ruleSetting) }
            with(response.storyStartSettings) { add(this.name); add(startSituation); add(prologue) }
            addAll(response.storySuggestedInputs)
            response.storyMainEvents.forEach { add(it.name); add(it.description); add(it.keySentence) }
            response.storyEndings.forEach { add(it.name); add(it.achievementCondition); add(it.epilogue) }
            response.characterIntroductions.forEach { it.description?.let(::add) }
        }
        if (name == null && texts.any(UsernameTokenRenderer::containsToken)) throw IllegalArgumentException("Missing protagonist name")
        return name
    } catch (ex: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 컴파일 응답의 기본 주인공 이름이 올바르지 않습니다.", ex)
    }
}
