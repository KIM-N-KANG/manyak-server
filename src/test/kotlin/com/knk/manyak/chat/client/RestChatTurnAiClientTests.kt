package com.knk.manyak.chat.client

import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper

/**
 * MockWebServer로 AI 채팅 턴 SSE 응답을 흉내 내, [RestChatTurnAiClient]가
 * token을 1:1 중계하고 completed를 [ChatTurnAiResult]로 매핑하며
 * error를 [ChatTurnAiException]으로 변환하는지 검증한다.
 */
class RestChatTurnAiClientTests {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `token을 1대1로 중계하고 completed를 결과로 매핑한다`() {
        server.enqueue(sseResponse(
            """
            event: token
            data: {"text":"검사장은 "}

            event: token
            data: {"text":"조용해졌다."}

            event: completed
            data: {"aiOutput":"검사장은 조용해졌다.","choices":["살핀다.","나선다.","벗어난다."]}

            """.trimIndent() + "\n",
        ))

        val tokens = mutableListOf<String>()
        val result = client().streamTurn(sampleRequest(), onToken = tokens::add)

        assertEquals(listOf("검사장은 ", "조용해졌다."), tokens, "token 청크를 순서대로 1:1 중계해야 한다")
        assertEquals("검사장은 조용해졌다.", result.aiOutput)
        assertEquals(listOf("살핀다.", "나선다.", "벗어난다."), result.choices)
    }

    @Test
    fun `completed에 choices가 없으면 빈 목록으로 처리한다`() {
        server.enqueue(sseResponse(
            """
            event: completed
            data: {"aiOutput":"choices 없는 응답"}

            """.trimIndent() + "\n",
        ))

        val result = client().streamTurn(sampleRequest(), onToken = {})

        assertEquals("choices 없는 응답", result.aiOutput)
        assertEquals(emptyList(), result.choices)
    }

    @Test
    fun `error 이벤트를 ChatTurnAiException으로 변환한다`() {
        server.enqueue(sseResponse(
            """
            event: error
            data: {"code":"LLM_ERROR","message":"AI가 실패했습니다."}

            """.trimIndent() + "\n",
        ))

        val exception = assertFailsWith<ChatTurnAiException> {
            client().streamTurn(sampleRequest(), onToken = {})
        }

        assertEquals("LLM_ERROR", exception.code)
        assertEquals("AI가 실패했습니다.", exception.message)
    }

    @Test
    fun `completed 없이 종료되면 백엔드 자체 오류로 던진다`() {
        server.enqueue(sseResponse(
            """
            event: token
            data: {"text":"끊긴 응답"}

            """.trimIndent() + "\n",
        ))

        // ChatTurnAiException(=AI relay)이 아니어야 ChatService가 자체 코드로 처리한다.
        assertFailsWith<IllegalStateException> {
            client().streamTurn(sampleRequest(), onToken = {})
        }
    }

    @Test
    fun `completed의 멀티바이트 문자가 청크 경계에서 깨지지 않는다`() {
        // 실 AI는 completed 본문을 청크 전송하므로 한글이 TCP 청크 경계에서 쪼개진다.
        // 작은 청크로 강제 분할해도 UTF-8 문자가 손실(U+FFFD)되지 않아야 한다.
        val aiOutput = "에리온의 손이 마법수정에서 떨어지는 순간, 주변 귀족들의 수군거림이 들려온다. " +
            "세리아는 눈을 동그랗게 뜨고 입을 가리며, 린은 무표정하게 고개를 숙인다. " +
            "무속성의 증명은 이제 확실해졌군요. 황량한 성소 안에서 그의 숨소리만이 공허하게 울린다."
        // 실제 스트림처럼 토큰 다수 뒤에 긴 completed가 오는 구조로 재현한다.
        val tokens = aiOutput.chunked(3).joinToString("") { chunk ->
            "event: token\ndata: {\"text\":\"$chunk\"}\n\n"
        }
        val sse = tokens +
            "event: completed\ndata: {\"aiOutput\":\"$aiOutput\",\"choices\":[\"증거를 숨긴다.\",\"앞으로 나선다.\"]}\n\n"
        val chunked = Buffer().writeUtf8(sse)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(chunked, 5), // 5바이트 청크 → 3바이트 한글 경계를 자른다
        )

        val relayed = StringBuilder()
        val result = client().streamTurn(sampleRequest(), onToken = { relayed.append(it) })

        assertTrue('�' !in result.aiOutput, "aiOutput에 깨진 문자(U+FFFD)가 없어야 한다")
        assertTrue('�' !in relayed.toString(), "중계 토큰에 깨진 문자(U+FFFD)가 없어야 한다")
        assertEquals(aiOutput, result.aiOutput)
        assertEquals(aiOutput, relayed.toString(), "중계 토큰을 이으면 aiOutput과 같아야 한다")
        assertEquals(listOf("증거를 숨긴다.", "앞으로 나선다."), result.choices)
    }

    @Test
    fun `요청을 snake_case 계약으로 채팅 턴 엔드포인트에 전송한다`() {
        server.enqueue(sseResponse(
            """
            event: completed
            data: {"aiOutput":"끝","choices":[]}

            """.trimIndent() + "\n",
        ))

        client().streamTurn(sampleRequest(), onToken = {})

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/chat/turns", recorded.path)
        assertTrue(recorded.getHeader("Accept")!!.contains("text/event-stream"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""story_settings""""), "snake_case로 직렬화해야 한다")
        assertTrue(body.contains(""""user_input":"마법수정에 손을 올린다.""""))
        assertTrue(body.contains(""""role":"USER""""), "role은 대문자로 직렬화해야 한다")
    }

    private fun client() = RestChatTurnAiClient(
        webClient = WebClient.builder().baseUrl(server.url("/").toString()).build(),
        objectMapper = JsonMapper.builder().build(),
        streamTimeout = Duration.ofSeconds(5),
    )

    private fun sseResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun sampleRequest() =
        ChatTurnAiRequest(
            genre = "판타지",
            storySettings = ChatTurnStorySettings(
                worldSetting = "마법 아카데미",
                characterSetting = "강진우",
                userRoleSetting = "신입생",
                ruleSetting = "마법은 속성 발현으로만 사용한다.",
            ),
            startSettings = ChatTurnStartSettings(
                name = "강진우",
                prologue = "입학식 전 적성 검사.",
                startSituation = "검사가 시작된다.",
            ),
            history = listOf(
                ChatHistoryMessage(ChatMessageRole.USER, "이름은 강진우야."),
                ChatHistoryMessage(ChatMessageRole.ASSISTANT, "강진우라는 이름이 기록판에 새겨졌다."),
            ),
            userInput = "마법수정에 손을 올린다.",
            summary = "",
        )
}
