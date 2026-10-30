package com.knk.manyak.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminOpenApiValidationIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var mapper: ObjectMapper
    private lateinit var api: JsonNode
    @BeforeEach fun readDocs() {
        api = mapper.readTree(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    }
    private fun schema(name: String) = api["components"]["schemas"][name]
    private fun values(node: JsonNode) = node.toList().map { it.asText() }
    private fun query(path: String, name: String) = api["paths"]["/api/v1/admin$path"]["get"]["parameters"].first { it["name"].asText() == name }["schema"]
    private fun types(node: JsonNode) = node["type"].let { if (it.isArray) values(it).toSet() else setOf(it.asText()) }

    @Test fun `스토리와 채팅 목록의 선택 필터는 허용 값만 명세에 노출한다`() {
        assertThat(values(schema("AdminStoriesRequest")["properties"]["creationMode"]["enum"])).containsExactlyInAnyOrder("GENERAL", "SIMPLE")
        for (name in listOf("AdminStoriesRequest", "AdminChatsRequest")) {
            val dto = schema(name)
            assertThat(values(dto["properties"]["deleted"]["enum"])).containsExactlyInAnyOrder("include", "only")
            assertThat(dto.path("required").toList().map { it.asText() }).doesNotContain("deleted", "creationMode")
        }
    }
    @Test fun `리텐션 시작 집단과 모든 회원 구분 쿼리는 enum을 제공한다`() {
        assertThat(values(query("/users/retention", "cohort")["enum"])).containsExactlyInAnyOrder("visitor", "signup", "first-chat")
        assertThat(values(schema("RetentionResponse")["properties"]["cohort"]["enum"])).containsExactlyInAnyOrder("visitor", "signup", "first-chat")
        for (path in listOf("/users/sessions", "/users/session-lengths", "/quality/client", "/quality/chat-options", "/conversion/turn-reach")) {
            assertThat(values(query(path, "segment")["enum"])).describedAs(path).containsExactlyInAnyOrder("all", "guest", "member")
        }
    }
    @Test fun `공개 중단 명세는 기타 사유의 조건부 필수와 다른 사유의 null만 허용한다`() {
        val request = schema("StoryTakedownRequest")
        assertThat(values(request["required"])).containsExactly("reasonCode")
        assertThat(values(request["properties"]["reasonCode"]["enum"])).containsExactlyInAnyOrder("SEXUAL_CONTENT", "COPYRIGHT_INFRINGEMENT", "HATE_SPEECH", "ETC")
        assertThat(types(request["properties"]["reasonDetail"])).containsExactlyInAnyOrder("string", "null")
        val branches = request["oneOf"].toList()
        assertThat(branches).hasSize(2)
        val etc = branches.single { values(it["properties"]["reasonCode"]["enum"]) == listOf("ETC") }
        val predefined = branches.single { it !== etc }
        assertThat(values(etc["required"])).contains("reasonCode", "reasonDetail")
        assertThat(types(etc["properties"]["reasonDetail"])).containsExactly("string")
        assertThat(values(predefined["required"])).containsExactly("reasonCode")
        assertThat(values(predefined["properties"]["reasonCode"]["enum"])).containsExactlyInAnyOrder("SEXUAL_CONTENT", "COPYRIGHT_INFRINGEMENT", "HATE_SPEECH")
        assertThat(types(predefined["properties"]["reasonDetail"])).containsExactly("null")
        for ((property, max) in listOf(request["properties"]["memo"] to 500, etc["properties"]["reasonDetail"] to 100)) {
            assertThat(property["minLength"].asInt()).isEqualTo(1)
            assertThat(property["maxLength"].asInt()).isEqualTo(max)
            val pattern = Regex(property["pattern"].asText())
            assertThat(pattern.matches(" \n\t\u3000")).isFalse()
            assertThat(pattern.matches("사유")).isTrue()
        }
        assertThat(types(request["properties"]["memo"])).contains("string", "null")
    }
    @Test fun `공개 중단 응답의 모든 필드는 필수이며 공개 범위는 PRIVATE다`() {
        val response = schema("StoryTakedownResponse")
        assertThat(values(response["required"])).containsExactlyInAnyOrder("storyId", "visibility", "takedownReason", "takedownReasonDetail", "takenDownAt")
        assertThat(values(response["properties"]["visibility"]["enum"])).containsExactly("PRIVATE")
        assertThat(types(response["properties"]["takedownReasonDetail"])).contains("string", "null")
    }
    @Test fun `이미 구현된 공지 알림 문구의 필수 길이와 패턴도 api-docs에 남긴다`() {
        val create = schema("CreateNoticeRequest")
        assertThat(values(create["required"])).contains("notificationTitle", "notificationBody")
        for ((field, max) in listOf("notificationTitle" to 100, "notificationBody" to 300)) {
            assertThat(create["properties"][field]["maxLength"].asInt()).isEqualTo(max)
            assertThat(create["properties"][field].has("pattern")).isTrue()
            val update = schema("UpdateNoticeRequest")["properties"][field]
            assertThat(update["minLength"].asInt()).isEqualTo(1)
            assertThat(update["maxLength"].asInt()).isEqualTo(max)
            assertThat(schema("AdminNoticeResponse")["properties"].has(field)).isTrue()
        }
    }
}
