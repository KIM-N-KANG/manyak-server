package com.knk.manyak.story.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * `manyak.ai.story.stub` 값에 따라 스토리 AI 클라이언트 빈이 상호배타로 배선되는지 검증한다.
 * 채팅 [com.knk.manyak.chat.client.ChatTurnAiClientWiringIntegrationTests] 미러링.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "manyak.ai.story.stub=false",
        "manyak.ai.base-url=http://localhost:8000",
    ],
)
class StoryAiClientRestWiringIntegrationTests {

    @Autowired
    private lateinit var storyAiClient: StoryAiClient

    @Test
    fun `stub이 false면 RestStoryAiClient가 주입된다`() {
        assertThat(storyAiClient).isInstanceOf(RestStoryAiClient::class.java)
    }
}

@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "manyak.ai.story.stub=true",
    ],
)
class StoryAiClientStubWiringIntegrationTests {

    @Autowired
    private lateinit var storyAiClient: StoryAiClient

    @Test
    fun `stub이 true면 StubStoryAiClient가 주입된다`() {
        assertThat(storyAiClient).isInstanceOf(StubStoryAiClient::class.java)
    }

    @Test
    fun `stub의 compile은 AI 호출 없이 필수 필드가 채워진 응답을 즉시 반환한다`() {
        val compiled = storyAiClient.compileStory(
            AiStoryCompileRequest(
                genreTags = listOf("판타지"),
                protagonistTags = listOf("신중한"),
                supportingTags = emptyList(),
                selectedStoryline = "선택된 스토리라인",
                additionalInfo = "추가 정보",
            ),
        )

        assertThat(compiled.stories.title).isNotBlank()
        assertThat(compiled.storyStartSettings.prologue).isNotBlank()
        assertThat(compiled.storySuggestedInputs).isNotEmpty()
    }

    @Test
    fun `stub의 storylines는 3개를 즉시 반환한다`() {
        val response = storyAiClient.createStorylines(
            AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonistTags = listOf("신중한"),
                supportingTags = emptyList(),
            ),
        )

        assertThat(response.stories).hasSize(3)
        assertThat(response.stories.map { it.id }).containsExactly(1, 2, 3)
    }
}
