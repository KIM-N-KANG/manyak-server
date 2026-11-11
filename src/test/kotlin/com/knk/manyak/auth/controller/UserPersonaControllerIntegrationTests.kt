package com.knk.manyak.auth.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import com.knk.manyak.auth.entity.UserPersona
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant

class UserPersonaControllerIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var mapper: ObjectMapper
    private lateinit var user: User
    @Autowired private lateinit var personas: com.knk.manyak.auth.repository.UserPersonaRepository
    private lateinit var auth: String
    private val path = "/api/v1/users/me/personas"
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        user = users.save(User(nickname = "작가"))
        auth = "Bearer ${jwt.issueAccessToken(user.publicId)}"
    }
    private fun post(body: String) = client.post().uri(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun patch(id: String, body: String) = client.patch().uri("$path/$id").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    @Test fun `목록은 생성 시각 오름차순이고 같은 시각이면 내부 ID 오름차순이다`() {
        val base = Instant.parse("2026-10-01T00:00:00Z")
        fun save(name: String, seconds: Long) = personas.saveAndFlush(
            UserPersona(userId = user.id, name = name, description = "설명", createdAt = base.plusSeconds(seconds)),
        )
        val newest = save("최신", 30)
        val tiedFirst = save("같은 시각 첫째", 10)
        val oldest = save("최초", 0)
        val tiedSecond = save("같은 시각 둘째", 10)
        val middle = save("중간", 20)

        val expected = listOf(oldest, tiedFirst, tiedSecond, middle, newest)
        val body = client.get().uri(path).header("Authorization", auth).exchange()
            .expectStatus().isOk.expectBody().returnResult().responseBody!!
        val rows = mapper.readTree(body)
        kotlin.test.assertEquals(expected.size, rows.size())
        expected.forEachIndexed { index, persona ->
            kotlin.test.assertEquals(persona.publicId.toString(), rows[index].path("id").asText())
        }
    }
    @Test fun `등록 수정 목록 삭제와 소유권`() {
        val bytes = post("""{"name":" 민우 ","description":" 탐험가 "}""").expectStatus().isCreated.expectBody().jsonPath("$.name").isEqualTo("민우").returnResult().responseBody!!
        val id = mapper.readTree(bytes).path("id").asText()
        patch(id, """{"name":null,"description":" 마법사 "}""").expectStatus().isOk.expectBody().jsonPath("$.name").isEqualTo("민우").jsonPath("$.description").isEqualTo("마법사")
        patch(id, "{}").expectStatus().isBadRequest
        patch(id, """{"name":" "}""").expectStatus().isBadRequest.expectBody().jsonPath("$.details[0].field").isEqualTo("name")
        client.get().uri(path).header("Authorization", auth).exchange().expectStatus().isOk.expectBody().jsonPath("$[0].id").isEqualTo(id)
        val originalAuth = auth
        auth = "Bearer ${jwt.issueAccessToken(users.save(User(nickname = "타인")).publicId)}"
        patch(id, """{"name":"다른 이름"}""").expectStatus().isNotFound
        auth = originalAuth
        client.delete().uri("$path/$id").header("Authorization", auth).exchange().expectStatus().isNoContent
        patch(id, """{"name":"다른 이름"}""").expectStatus().isNotFound
        client.delete().uri("$path/$id").header("Authorization", auth).exchange().expectStatus().isNotFound
    }
    @Test fun `인증 형식 검증 상한`() {
        client.get().uri(path).exchange().expectStatus().isUnauthorized
        patch("bad-id", """{"name":"정상"}""").expectStatus().isNotFound
        post("{}").expectStatus().isBadRequest
        post("""{"name":"${"가".repeat(21)}","description":"설명"}""").expectStatus().isBadRequest
        post("""{"name":"이름","description":"${"가".repeat(1001)}"}""").expectStatus().isBadRequest
        repeat(10) { post("""{"name":"같은 이름","description":"설명"}""").expectStatus().isCreated }
        post("""{"name":"초과","description":"설명"}""").expectStatus().isEqualTo(409)
    }
    @Test fun `정지 회원의 페르소나 PATCH는 403이고 원본이 유지된다`() {
        val id = createThenSuspend()
        patch(id, """{"name":"변경","description":"변경 설명"}""").expectStatus().isForbidden
        val stored = personas.findAll().single()
        kotlin.test.assertEquals("원래 이름", stored.name)
        kotlin.test.assertEquals("원래 설명", stored.description)
        kotlin.test.assertNull(stored.deletedAt)
        client.get().uri(path).header("Authorization", auth).exchange().expectStatus().isOk
    }
    @Test fun `정지 회원의 페르소나 DELETE는 403이고 삭제되지 않는다`() {
        val id = createThenSuspend()
        client.delete().uri("$path/$id").header("Authorization", auth).exchange().expectStatus().isForbidden
        kotlin.test.assertNull(personas.findAll().single().deletedAt)
    }
    private fun createThenSuspend(): String {
        val bytes = post("""{"name":"원래 이름","description":"원래 설명"}""").expectStatus().isCreated.expectBody().returnResult().responseBody!!
        user.status = com.knk.manyak.auth.dto.UserStatus.SUSPENDED
        users.saveAndFlush(user)
        return mapper.readTree(bytes).path("id").asText()
    }
}
