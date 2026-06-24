package com.knk.manyak.story.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `storyline 응답의 meta를 snake case로 역직렬화하고 도메인 meta로 정규화한다`() {
        val response = objectMapper.readValue(
            """
            {
              "stories": [],
              "meta": {
                "model": "deepseek-v4-pro",
                "provider": "deepseek",
                "input_token_count": 6180,
                "output_token_count": 512,
                "retry_count": 1,
                "prompt_versions": {"STORYLINES": 2}
              }
            }
            """.trimIndent(),
            AiStorylinesResponse::class.java,
        )

        val meta = requireNotNull(response.meta)
        assertEquals("deepseek-v4-pro", meta.model)
        assertEquals("deepseek", meta.provider)
        assertEquals(6180, meta.inputTokenCount)
        assertEquals(512, meta.outputTokenCount)
        assertEquals(1, meta.retryCount)
        assertEquals(mapOf("STORYLINES" to 2), meta.promptVersions)

        val domain = meta.toAiCallMeta()
        assertEquals("deepseek-v4-pro", domain.model)
        assertEquals(6180, domain.inputTokenCount)
        assertEquals(mapOf("STORYLINES" to 2), domain.promptVersions)
    }

    @Test
    fun `compile 응답의 meta를 역직렬화한다`() {
        val response = objectMapper.readValue(
            """
            {
              "stories": {"title": "제목", "one_line_intro": "소개", "description": "설명"},
              "story_settings": {"world_setting": "w", "character_setting": "c", "user_role_setting": "u", "rule_setting": "r"},
              "story_start_settings": {"name": "n", "start_situation": "s", "prologue": "p"},
              "story_suggested_inputs": ["a"],
              "meta": {"model": "deepseek-v4-pro", "prompt_versions": {"COMPILE": 2}}
            }
            """.trimIndent(),
            AiStoryCompileResponse::class.java,
        )

        assertEquals("deepseek-v4-pro", response.meta?.model)
        assertEquals(mapOf("COMPILE" to 2), response.meta?.promptVersions)
    }

    @Test
    fun `meta가 없거나 최상위·meta에 미지 필드가 있어도 역직렬화가 깨지지 않는다`() {
        val noMeta = objectMapper.readValue("""{"stories": []}""", AiStorylinesResponse::class.java)
        assertNull(noMeta.meta)

        val withUnknown = objectMapper.readValue(
            """
            {
              "stories": [],
              "unexpected_top_field": "ignored",
              "meta": {"model": "m", "unexpected_meta_field": 99}
            }
            """.trimIndent(),
            AiStorylinesResponse::class.java,
        )
        assertEquals("m", withUnknown.meta?.model)
    }
}
