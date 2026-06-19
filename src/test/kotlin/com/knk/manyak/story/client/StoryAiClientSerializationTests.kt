package com.knk.manyak.story.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class StoryAiClientSerializationTests {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `AI 스토리라인 요청은 snake case 필드명으로 직렬화한다`() {
        val json = objectMapper.writeValueAsString(
            AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonistTags = listOf("기억상실"),
                supportingTags = listOf("비밀스러운 조력자"),
            ),
        )

        assertTrue(json.contains(""""genre_tags":["판타지"]"""))
        assertTrue(json.contains(""""protagonist_tags":["기억상실"]"""))
        assertTrue(json.contains(""""supporting_tags":["비밀스러운 조력자"]"""))
        assertFalse(json.contains("genreTags"))
        assertFalse(json.contains("protagonistTags"))
        assertFalse(json.contains("supportingTags"))
    }

    @Test
    fun `AI 스토리라인 응답을 역직렬화한다`() {
        val response = objectMapper.readValue(
            """
            {
              "stories": [
                {
                  "id": 1,
                  "story": "기억을 잃은 주인공의 이야기",
                  "recommended_infos": ["첫 추가 정보", "두 번째 추가 정보", "세 번째 추가 정보"]
                }
              ]
            }
            """.trimIndent(),
            AiStorylinesResponse::class.java,
        )

        assertEquals(1, response.stories.single().id)
        assertEquals("기억을 잃은 주인공의 이야기", response.stories.single().story)
        assertEquals(listOf("첫 추가 정보", "두 번째 추가 정보", "세 번째 추가 정보"), response.stories.single().recommendedInfos)
    }
}
