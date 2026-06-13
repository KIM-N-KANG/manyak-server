package com.knk.manyak.story.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestStoryAiClientTests {

    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
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
                          "story": "생성 스토리",
                          "questions": ["질문 1", "질문 2", "질문 3"]
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
                genre_tags = listOf("판타지"),
                protagonist_tags = listOf("기억상실"),
                supporting_tags = listOf("비밀스러운 조력자"),
            ),
        )

        assertTrue(capturedContentType?.startsWith("application/json") == true)
        assertEquals(null, capturedUpgrade)
        assertTrue(requireNotNull(capturedBody).contains(""""genre_tags":["판타지"]"""))
        assertTrue(requireNotNull(capturedBody).contains(""""protagonist_tags":["기억상실"]"""))
        assertTrue(requireNotNull(capturedBody).contains(""""supporting_tags":["비밀스러운 조력자"]"""))
        assertEquals("생성 스토리", response.stories.single().story)
    }

    private fun HttpExchange.respondJson(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, body.toByteArray().size.toLong())
        responseBody.use { outputStream ->
            outputStream.write(body.toByteArray())
        }
    }
}
