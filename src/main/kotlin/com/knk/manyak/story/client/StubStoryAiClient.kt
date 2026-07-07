package com.knk.manyak.story.client

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 로컬 개발용 스토리 AI 스텁.
 *
 * 실제 AI(LLM) 호출 없이 storylines·compile을 결정적 더미로 즉시 반환한다. compile은 실제 LLM 생성이라
 * 수십 초~2분 걸려(백엔드 read timeout 120s) 로컬 `http/` 수동 검증의 스토리 준비(storylines→simple)
 * 단계를 막으므로, `manyak.ai.story.stub=true`일 때만 빈으로 등록해 즉답 처리한다.
 * 채팅 [StubChatTurnAiClient]와 동일한 패턴이며, [RestStoryAiClient]와 상호배타로 동작한다.
 */
@Component
@ConditionalOnProperty(name = ["manyak.ai.story.stub"], havingValue = "true")
class StubStoryAiClient : StoryAiClient {

    override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse =
        AiStorylinesResponse(
            stories = (1..STORYLINE_COUNT).map { index ->
                AiStoryItem(
                    id = index,
                    storyline = "[스텁] 스토리라인 $index — ${request.genreTags.joinToString(", ").ifBlank { "장르 미지정" }}",
                    recommendedInfos = (1..RECOMMENDED_INFO_COUNT).map { infoIndex -> "추천 정보 $index-$infoIndex" },
                )
            },
            meta = stubMeta(),
        )

    override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse =
        AiStoryCompileResponse(
            stories = AiStoryMeta(
                title = "[스텁] ${request.selectedStoryline.trim().take(TITLE_PREVIEW_LENGTH).ifBlank { "제목 없음" }}",
                oneLineIntro = "스텁이 생성한 한 줄 소개입니다.",
                description = "로컬 스텁이 반환한 더미 스토리입니다. 실제 AI 컴파일을 대체합니다.",
            ),
            storySettings = AiStorySettings(
                worldSetting = "# 세계관\n스텁 세계관 설정입니다.",
                characterSetting = "# 등장인물\n스텁 등장인물 설정입니다.",
                userRoleSetting = "# 주인공\n스텁 주인공 설정입니다.",
                ruleSetting = "# 전개 규칙\n스텁 전개 규칙입니다.",
            ),
            storyStartSettings = AiStoryStartSettings(
                name = "스텁 시작 설정",
                startSituation = "스텁이 반환한 시작 상황입니다.",
                prologue = "스텁이 반환한 프롤로그입니다. 여기서 이야기가 시작됩니다.",
            ),
            storySuggestedInputs = listOf(
                "주변을 둘러본다.",
                "한 걸음 앞으로 나선다.",
                "상황을 지켜본다.",
            ),
            meta = stubMeta(),
        )

    private fun stubMeta(): AiResponseMeta = AiResponseMeta(model = "stub", provider = "stub")

    private companion object {
        const val STORYLINE_COUNT = 3
        const val RECOMMENDED_INFO_COUNT = 3
        const val TITLE_PREVIEW_LENGTH = 12
    }
}
