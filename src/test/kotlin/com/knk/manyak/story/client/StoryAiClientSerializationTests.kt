package com.knk.manyak.story.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class StoryAiClientSerializationTests {

    // 코틀린 모듈을 붙여 운영(Spring 자동 등록)과 같은 매퍼로 검증한다. 이게 없으면 응답에 없는 필드가
    // 기본값 대신 null로 들어가, 하위호환(신규 필드를 아직 안 보내는 AI 버전) 검증이 성립하지 않는다.
    private val objectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    // 실제 compile 응답은 story_main_events(항상 3~5)·story_endings(항상 존재)를 포함한다(AI 계약 §5-3-3).
    private val COMPILE_RESPONSE_JSON =
        """
        {
          "stories": {"title": "제목", "one_line_intro": "소개", "description": "설명"},
          "story_settings": {"world_setting": "w", "character_setting": "c", "user_role_setting": "u", "rule_setting": "r"},
          "story_start_settings": {"name": "n", "start_situation": "s", "prologue": "p"},
          "story_suggested_inputs": ["a"],
          "story_main_events": [
            {"name": "발단", "description": "이야기가 시작된다", "key_sentence": "주인공이 길을 나선다"}
          ],
          "story_endings": [
            {"name": "해피", "min_turns": 5, "achievement_condition": "적을 물리친다", "epilogue": "따뜻한 에필로그"}
          ],
          "meta": {"model": "deepseek-v4-pro", "prompt_versions": {"COMPILE": 2}}
        }
        """.trimIndent()

    @Test
    fun `AI 스토리라인 요청은 인물 단위 snake case 필드명으로 직렬화한다`() {
        val json = objectMapper.writeValueAsString(
            AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(name = "아린", gender = "FEMALE", features = listOf("기억상실")),
                supportingCharacters = listOf(AiCharacter(features = listOf("비밀스러운 조력자"))),
            ),
        )

        assertTrue(json.contains(""""genre_tags":["판타지"]"""))
        assertTrue(json.contains(""""protagonist":{"""))
        assertTrue(json.contains(""""name":"아린""""))
        assertTrue(json.contains(""""gender":"FEMALE""""))
        assertTrue(json.contains(""""features":["기억상실"]"""))
        assertTrue(json.contains(""""supporting_characters":[{"""))
        assertTrue(json.contains(""""features":["비밀스러운 조력자"]"""))
        assertFalse(json.contains("genreTags"))
        assertFalse(json.contains("supportingCharacters"))
        assertFalse(json.contains("protagonist_tags"))
        assertFalse(json.contains("supporting_tags"))
    }

    @Test
    fun `AI 스토리라인 요청은 주인공 입력이 비어도 protagonist 객체를 생략하지 않는다`() {
        val json = objectMapper.writeValueAsString(
            AiStorylinesRequest(
                genreTags = emptyList(),
                protagonist = AiCharacter(),
            ),
        )

        // 주인공은 AI 쪽 기본값 없는 필수 필드다 — 누락하면 422이므로 빈 입력도 객체로 실린다.
        assertTrue(json.contains(""""protagonist":{"""))
        assertTrue(json.contains(""""supporting_characters":[]"""))
    }

    @Test
    fun `AI 스토리 완성 요청은 인물 단위와 additional_info 필드명으로 직렬화한다`() {
        val json = objectMapper.writeValueAsString(
            AiStoryCompileRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(name = "아린", features = listOf("기억상실")),
                supportingCharacters = listOf(AiCharacter(name = "레온", features = listOf("비밀스러운 조력자"))),
                selectedStoryline = "선택한 스토리라인",
                additionalInfo = "주인공은 신중하다",
            ),
        )

        assertTrue(json.contains(""""additional_info":"주인공은 신중하다""""))
        assertTrue(json.contains(""""selected_storyline":"선택한 스토리라인""""))
        assertTrue(json.contains(""""protagonist":{"""))
        assertTrue(json.contains(""""supporting_characters":[{"""))
        assertFalse(json.contains("extra_info"))
        assertFalse(json.contains("additionalInfo"))
        assertFalse(json.contains("protagonist_tags"))
        assertFalse(json.contains("supporting_tags"))
    }

    @Test
    fun `AI 스토리라인 응답을 역직렬화한다`() {
        val response = objectMapper.readValue(
            """
            {
              "stories": [
                {
                  "id": 1,
                  "storyline": "기억을 잃은 주인공의 이야기",
                  "recommended_infos": ["첫 추가 정보", "두 번째 추가 정보", "세 번째 추가 정보"]
                }
              ]
            }
            """.trimIndent(),
            AiStorylinesResponse::class.java,
        )

        assertEquals(1, response.stories.single().id)
        assertEquals("기억을 잃은 주인공의 이야기", response.stories.single().storyline)
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
        val response = objectMapper.readValue(COMPILE_RESPONSE_JSON, AiStoryCompileResponse::class.java)

        assertEquals("deepseek-v4-pro", response.meta?.model)
        assertEquals(mapOf("COMPILE" to 2), response.meta?.promptVersions)
    }

    @Test
    fun `compile 응답의 주요 사건과 엔딩을 snake case 필드로 역직렬화한다`() {
        val response = objectMapper.readValue(COMPILE_RESPONSE_JSON, AiStoryCompileResponse::class.java)

        val event = response.storyMainEvents.single()
        assertEquals("발단", event.name)
        assertEquals("이야기가 시작된다", event.description)
        assertEquals("주인공이 길을 나선다", event.keySentence)

        val ending = response.storyEndings.single()
        assertEquals("해피", ending.name)
        assertEquals(5, ending.minTurns)
        assertEquals("적을 물리친다", ending.achievementCondition)
        assertEquals("따뜻한 에필로그", ending.epilogue)
    }

    @Test
    fun `compile 응답의 인물 외형과 이미지를 snake case 필드로 역직렬화한다`() {
        val response = objectMapper.readValue(
            """
            {
              "stories": {"title": "제목", "one_line_intro": "소개", "description": "설명"},
              "story_settings": {"world_setting": "w", "character_setting": "c", "user_role_setting": "u", "rule_setting": "r"},
              "story_start_settings": {"name": "n", "start_situation": "s", "prologue": "p"},
              "story_suggested_inputs": ["a"],
              "character_appearances": [
                {"name": "서준", "gender": "MALE", "age": "20대", "body": "마른 체형", "face": "선한 눈매",
                 "hair": "검은 단발", "outfit": "교복", "visual_identity": "왼쪽 눈 밑 점"},
                {"name": "외형없음"}
              ],
              "character_images": [
                {"name": "서준", "image_base64": "AAAA", "content_type": "image/webp"},
                {"name": "하나", "image_base64": null, "content_type": null, "error": "rate_limited"}
              ]
            }
            """.trimIndent(),
            AiStoryCompileResponse::class.java,
        )

        val appearance = response.characterAppearances.first()
        assertEquals("서준", appearance.name)
        assertEquals("MALE", appearance.gender)
        assertEquals("왼쪽 눈 밑 점", appearance.visualIdentity)
        // LLM이 못 채운 칸은 null이지만 항목 자체는 존재한다.
        assertNull(response.characterAppearances[1].hair)

        val success = response.characterImages.first()
        assertEquals("AAAA", success.imageBase64)
        assertEquals("image/webp", success.contentType)
        assertNull(success.error)

        val failed = response.characterImages[1]
        assertNull(failed.imageBase64)
        assertEquals("rate_limited", failed.error)
    }

    @Test
    fun `인물 필드가 없는 compile 응답은 빈 배열로 역직렬화한다`() {
        // 신규 필드를 아직 싣지 않는 AI 버전과의 하위호환(story_main_events와 같은 관례).
        val response = objectMapper.readValue(COMPILE_RESPONSE_JSON, AiStoryCompileResponse::class.java)

        assertTrue(response.characterAppearances.isEmpty())
        assertTrue(response.characterImages.isEmpty())
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
