package com.knk.manyak.chat.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubChatTurnAiClientTests {

    private val client = StubChatTurnAiClient()

    @Test
    fun `토큰 청크를 순차 발행한 뒤 누적 결과를 반환한다`() {
        val tokens = mutableListOf<String>()

        val result = client.streamTurn(request(userInput = "마법수정에 손을 올린다."), onToken = tokens::add)

        assertTrue(tokens.isNotEmpty(), "토큰 청크가 발행되어야 한다")
        assertEquals(result.aiOutput, tokens.joinToString(""), "발행된 청크를 이으면 aiOutput과 같아야 한다")
    }

    @Test
    fun `각 토큰은 멀티-char 청크로 발행된다`() {
        val tokens = mutableListOf<String>()

        client.streamTurn(request(userInput = "충분히 긴 사용자 입력을 넣어 본다."), onToken = tokens::add)

        assertTrue(tokens.any { it.length > 1 }, "적어도 하나의 청크는 멀티-char 여야 한다")
    }

    @Test
    fun `결과는 user_input을 반영하고 선택지를 포함한다`() {
        val userInput = "마법수정에 손을 올린다."

        val result = client.streamTurn(request(userInput = userInput), onToken = {})

        assertTrue(result.aiOutput.contains(userInput), "응답은 사용자 입력을 반영해야 한다")
        assertTrue(result.choices.isNotEmpty(), "다음 행동 선택지를 포함해야 한다")
    }

    private fun request(userInput: String) =
        ChatTurnAiRequest(
            genre = "판타지",
            story_settings = ChatTurnStorySettings(
                world = "마법 아카데미",
                character = "강진우",
                user_role = "신입생",
                rule = "마법은 속성 발현으로만 사용한다.",
            ),
            start_settings = ChatTurnStartSettings(
                name = "강진우",
                prologue = "입학식 전 적성 검사.",
                start_situation = "검사가 시작된다.",
            ),
            history = emptyList(),
            user_input = userInput,
            summary = "",
        )
}
