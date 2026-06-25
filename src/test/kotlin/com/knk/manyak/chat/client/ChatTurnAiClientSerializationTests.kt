package com.knk.manyak.chat.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class ChatTurnAiClientSerializationTests {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `채팅 턴 요청은 snake case 필드명으로 직렬화한다`() {
        val json = objectMapper.writeValueAsString(sampleRequest())

        assertTrue(json.contains(""""genre":"판타지""""))
        assertTrue(json.contains(""""story_settings""""))
        assertTrue(json.contains(""""world_setting":"마법 아카데미가 존재하는 세계""""))
        assertTrue(json.contains(""""character_setting":"강진우, 무속성 판정을 받은 1학년""""))
        assertTrue(json.contains(""""user_role_setting":"무속성 신입생""""))
        assertTrue(json.contains(""""rule_setting":"마법은 속성 발현으로만 사용할 수 있다.""""))
        assertTrue(json.contains(""""start_settings""""))
        assertTrue(json.contains(""""start_situation":"적성 검사가 시작된다.""""))
        assertTrue(json.contains(""""user_input":"마법수정에 손을 올린다.""""))
        assertTrue(json.contains(""""summary":""""))
        assertFalse(json.contains("storySettings"))
        assertFalse(json.contains("startSettings"))
        assertFalse(json.contains("userRole"))
        assertFalse(json.contains("userInput"))
        assertFalse(json.contains("startSituation"))
    }

    @Test
    fun `history의 role은 대문자로 직렬화한다`() {
        val json = objectMapper.writeValueAsString(sampleRequest())

        assertTrue(json.contains(""""role":"USER""""))
        assertTrue(json.contains(""""role":"ASSISTANT""""))
        assertFalse(json.contains(""""role":"user""""))
        assertFalse(json.contains(""""role":"assistant""""))
    }

    @Test
    fun `completed 결과의 aiOutput과 choices를 역직렬화한다`() {
        val result = objectMapper.readValue(
            """
            {
              "aiOutput": "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.",
              "choices": ["주변을 살핀다.", "앞으로 나선다.", "자리를 벗어난다."]
            }
            """.trimIndent(),
            ChatTurnAiResult::class.java,
        )

        assertEquals("검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.", result.aiOutput)
        assertEquals(listOf("주변을 살핀다.", "앞으로 나선다.", "자리를 벗어난다."), result.choices)
    }

    private fun sampleRequest() =
        ChatTurnAiRequest(
            genre = "판타지",
            storySettings = ChatTurnStorySettings(
                worldSetting = "마법 아카데미가 존재하는 세계",
                characterSetting = "강진우, 무속성 판정을 받은 1학년",
                userRoleSetting = "무속성 신입생",
                ruleSetting = "마법은 속성 발현으로만 사용할 수 있다.",
            ),
            startSettings = ChatTurnStartSettings(
                name = "강진우",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사가 시작된다.",
            ),
            history = listOf(
                ChatHistoryMessage(ChatMessageRole.USER, "이름은 강진우야."),
                ChatHistoryMessage(ChatMessageRole.ASSISTANT, "강진우라는 이름이 기록판에 새겨졌다."),
            ),
            userInput = "마법수정에 손을 올린다.",
            summary = "",
        )
}
