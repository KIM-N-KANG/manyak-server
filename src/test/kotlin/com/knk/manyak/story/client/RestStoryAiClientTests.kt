package com.knk.manyak.story.client

import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.global.observability.MdcKeys
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.MDC
import org.springframework.web.client.RestClientException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestStoryAiClientTests {

    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        MDC.clear()
    }

    @Test
    fun `AI 서버에 JSON body와 content type을 전달한다`() {
        var capturedContentType: String? = null
        var capturedUpgrade: String? = null
        var capturedBody: String? = null
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/story/storylines") { exchange ->
                capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
                capturedUpgrade = exchange.requestHeaders.getFirst("Upgrade")
                capturedBody = exchange.requestBody.bufferedReader().readText()
                exchange.respondJson(
                    """
                    {
                      "stories": [
                        {
                          "id": 1,
                          "storyline": "생성 스토리",
                          "recommended_infos": ["추가 정보 1", "추가 정보 2", "추가 정보 3"]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            }
            start()
        }

        val port = requireNotNull(server).address.port
        val response = RestStoryAiClient("http://localhost:$port").createStorylines(
            AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(features = listOf("기억상실")),
                supportingCharacters = listOf(AiCharacter(features = listOf("비밀스러운 조력자"))),
            ),
        )

        assertTrue(capturedContentType?.startsWith("application/json") == true)
        assertEquals(null, capturedUpgrade)
        assertTrue(requireNotNull(capturedBody).contains(""""genre_tags":["판타지"]"""))
        assertTrue(requireNotNull(capturedBody).contains(""""features":["기억상실"]"""))
        assertTrue(requireNotNull(capturedBody).contains(""""supporting_characters":[{"""))
        assertEquals("생성 스토리", response.stories.single().storyline)
    }

    @Test
    fun `MDC 상관관계 식별자를 outbound 헤더로 forward한다`() {
        var requestId: String? = null
        var sessionId: String? = null
        var deviceIdHash: String? = null
        var deviceIdRaw: String? = null
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/story/storylines") { exchange ->
                requestId = exchange.requestHeaders.getFirst("X-Manyak-Request-Id")
                sessionId = exchange.requestHeaders.getFirst("X-Manyak-Session-Id")
                deviceIdHash = exchange.requestHeaders.getFirst("X-Manyak-Device-Id-Hash")
                deviceIdRaw = exchange.requestHeaders.getFirst("X-Manyak-Device-Id")
                exchange.respondJson(STORYLINES_RESPONSE_JSON)
            }
            start()
        }

        val port = requireNotNull(server).address.port
        MDC.put(MdcKeys.REQUEST_ID, "req_story_1")
        MDC.put(MdcKeys.SESSION_ID, "sess_story_1")
        MDC.put(MdcKeys.DEVICE_ID_HASH, "device_hash_story")
        RestStoryAiClient("http://localhost:$port").createStorylines(
            AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(),
            ),
        )

        assertEquals("req_story_1", requestId)
        assertEquals("sess_story_1", sessionId)
        assertEquals("device_hash_story", deviceIdHash)
        assertNull(deviceIdRaw, "원본 익명 ID 헤더는 forward하지 않는다")
    }

    @Test
    fun `스토리라인 호출에 연결 식별자 헤더를 싣는다`() {
        val captured = mutableMapOf<String, String?>()
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/story/storylines") { exchange ->
                TRACE_HEADER_NAMES.forEach { captured[it] = exchange.requestHeaders.getFirst(it) }
                exchange.respondJson(STORYLINES_RESPONSE_JSON)
            }
            start()
        }

        val port = requireNotNull(server).address.port
        val creationId = UUID.randomUUID()
        val parentCreationId = UUID.randomUUID()
        RestStoryAiClient("http://localhost:$port").createStorylines(
            AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(),
            ),
            AiTraceLink(creationId = creationId, parentCreationId = parentCreationId, isRegenerated = true),
        )

        assertEquals(creationId.toString(), captured["X-Manyak-Creation-Id"])
        assertEquals(parentCreationId.toString(), captured["X-Manyak-Parent-Creation-Id"])
        assertEquals("true", captured["X-Manyak-Is-Regenerated"])
    }

    @Test
    fun `연결 식별자가 없으면 해당 헤더를 생략한다`() {
        val captured = mutableMapOf<String, String?>()
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/story/storylines") { exchange ->
                TRACE_HEADER_NAMES.forEach { captured[it] = exchange.requestHeaders.getFirst(it) }
                exchange.respondJson(STORYLINES_RESPONSE_JSON)
            }
            start()
        }

        val port = requireNotNull(server).address.port
        RestStoryAiClient("http://localhost:$port").createStorylines(
            AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(),
            ),
        )

        TRACE_HEADER_NAMES.forEach { assertNull(captured[it], "$it 헤더는 값이 없으면 생략해야 한다") }
    }

    @Test
    fun `compile 호출에 스토리라인 연결 식별자 헤더를 싣는다`() {
        val captured = mutableMapOf<String, String?>()
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/story/compile") { exchange ->
                TRACE_HEADER_NAMES.forEach { captured[it] = exchange.requestHeaders.getFirst(it) }
                exchange.respondJson(COMPILE_RESPONSE_JSON)
            }
            start()
        }

        val port = requireNotNull(server).address.port
        val creationId = UUID.randomUUID()
        RestStoryAiClient("http://localhost:$port").compileStory(
            AiStoryCompileRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(),
                selectedStoryline = "선택된 스토리라인",
                additionalInfo = "추가 정보",
            ),
            AiTraceLink(creationId = creationId, storylineId = 42L, storylineOrder = 2),
        )

        assertEquals(creationId.toString(), captured["X-Manyak-Creation-Id"])
        // 스토리라인 id는 Long 그대로 나간다(스펙 §4-4).
        assertEquals("42", captured["X-Manyak-Storyline-Id"])
        assertEquals("2", captured["X-Manyak-Storyline-Order"])
    }

    @Test
    fun `AI 서버 응답이 지연되면 read timeout으로 실패한다`() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/story/storylines") { exchange ->
                exchange.requestBody.close()
                Thread.sleep(500)
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1)
            }
            start()
        }

        val port = requireNotNull(server).address.port
        val client = RestStoryAiClient(
            aiBaseUrl = "http://localhost:$port",
            connectTimeout = Duration.ofMillis(100),
            storylineReadTimeout = Duration.ofMillis(100),
            compileReadTimeout = Duration.ofMillis(100),
        )

        assertFailsWith<RestClientException> {
            client.createStorylines(
                AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(),
            ),
            )
        }
    }

    @Test
    fun `compile은 storyline과 독립된 read timeout을 사용한다`() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/story/storylines") { exchange ->
                exchange.requestBody.close()
                Thread.sleep(400)
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1)
            }
            createContext("/api/v1/story/compile") { exchange ->
                exchange.requestBody.close()
                Thread.sleep(400)
                exchange.respondJson(COMPILE_RESPONSE_JSON)
            }
            start()
        }

        val port = requireNotNull(server).address.port
        val client = RestStoryAiClient(
            aiBaseUrl = "http://localhost:$port",
            connectTimeout = Duration.ofMillis(500),
            storylineReadTimeout = Duration.ofMillis(150),
            compileReadTimeout = Duration.ofSeconds(3),
        )

        // storyline은 짧은 read timeout(150ms < 400ms 지연)으로 실패한다.
        assertFailsWith<RestClientException> {
            client.createStorylines(
                AiStorylinesRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(),
            ),
            )
        }

        // compile은 긴 read timeout(3s > 400ms 지연)으로 동일 지연을 견디고 성공한다.
        val compiled = client.compileStory(
            AiStoryCompileRequest(
                genreTags = listOf("판타지"),
                protagonist = AiCharacter(),
                selectedStoryline = "선택된 스토리라인",
                additionalInfo = "추가 정보",
            ),
        )

        assertEquals("제목", compiled.stories.title)
        assertEquals("프롤로그", compiled.storyStartSettings.prologue)
    }

    private fun HttpExchange.respondJson(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, body.toByteArray().size.toLong())
        responseBody.use { outputStream ->
            outputStream.write(body.toByteArray())
        }
    }

    companion object {
        /** KNK-751 연결 식별자 헤더. "값이 있으면 붙이고 없으면 생략"을 양방향으로 단언하기 위해 한곳에 모아 둔다. */
        private val TRACE_HEADER_NAMES = listOf(
            "X-Manyak-Creation-Id",
            "X-Manyak-Parent-Creation-Id",
            "X-Manyak-Storyline-Id",
            "X-Manyak-Storyline-Order",
            "X-Manyak-Is-Regenerated",
        )

        private val STORYLINES_RESPONSE_JSON =
            """
            {
              "stories": [
                {
                  "id": 1,
                  "storyline": "생성 스토리",
                  "recommended_infos": ["추가 정보 1", "추가 정보 2", "추가 정보 3"]
                }
              ]
            }
            """.trimIndent()

        private val COMPILE_RESPONSE_JSON =
            """
            {
              "stories": {
                "title": "제목",
                "one_line_intro": "한 줄 소개",
                "description": "설명"
              },
              "story_settings": {
                "world_setting": "세계관 설정",
                "character_setting": "캐릭터 설정",
                "user_role_setting": "역할 설정",
                "rule_setting": "규칙 설정"
              },
              "story_start_settings": {
                "name": "시작 이름",
                "start_situation": "시작 상황",
                "prologue": "프롤로그"
              },
              "story_suggested_inputs": ["입력 1", "입력 2"]
            }
            """.trimIndent()
    }
}
