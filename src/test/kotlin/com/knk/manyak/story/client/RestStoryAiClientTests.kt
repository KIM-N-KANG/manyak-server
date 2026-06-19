package com.knk.manyak.story.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.web.client.RestClientException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                genreTags = listOf("판타지"),
                protagonistTags = listOf("기억상실"),
                supportingTags = listOf("비밀스러운 조력자"),
            ),
        )

        assertTrue(capturedContentType?.startsWith("application/json") == true)
        assertEquals(null, capturedUpgrade)
        assertTrue(requireNotNull(capturedBody).contains(""""genre_tags":["판타지"]"""))
        assertTrue(requireNotNull(capturedBody).contains(""""protagonist_tags":["기억상실"]"""))
        assertTrue(requireNotNull(capturedBody).contains(""""supporting_tags":["비밀스러운 조력자"]"""))
        assertEquals("생성 스토리", response.stories.single().story)
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
            readTimeout = Duration.ofMillis(100),
        )

        assertFailsWith<RestClientException> {
            client.createStorylines(
                AiStorylinesRequest(
                    genreTags = listOf("판타지"),
                    protagonistTags = emptyList(),
                    supportingTags = emptyList(),
                ),
            )
        }
    }

    private fun HttpExchange.respondJson(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, body.toByteArray().size.toLong())
        responseBody.use { outputStream ->
            outputStream.write(body.toByteArray())
        }
    }
}
