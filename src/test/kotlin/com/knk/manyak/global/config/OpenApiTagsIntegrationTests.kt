package com.knk.manyak.global.config

import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * OpenAPI 태그와 서버 주소 정리(KNK-2124).
 * - 태그는 이름을 붙인 것만 쓴다. springdoc 기본 이름(`*-controller`)이 생기면 실패한다.
 * - 관리자 경로는 `Admin ` 태그, 앱 경로는 그 밖의 태그다. manyak-web이 태그 이름으로 생성 폴더를 나눈다.
 * - 상단 태그 목록(Swagger UI 묶음 순서)은 앱 태그를 이름순으로, 그 뒤에 관리자 태그를 이름순으로 둔다.
 * - servers는 상대 주소 `/` 하나다. TLS를 끝내는 프록시 뒤에서 생성 주소가 http로 나오던 문제를 막는다.
 * - operationId는 태그 정리 전과 같다. manyak-admin과 manyak-web이 operationId로 생성 코드 이름을 만든다.
 *   목록은 `openapi/operation-ids.txt`(정리 전 이 테스트 컨텍스트에서 뽑은 값)에 고정한다.
 *   테스트 전용 탐침 3개가 앞에 끼어 일부 순번(`get_N`)이 dev 스펙보다 하나씩 크다. 그 밖의 183개는 dev 스펙과 같다.
 *   엔드포인트를 더하거나 지우면 이 파일을 같이 고친다.
 */
class OpenApiTagsIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var mapper: ObjectMapper
    private lateinit var api: JsonNode

    @BeforeEach fun readDocs() {
        api = mapper.readTree(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    }

    private data class Op(val method: String, val path: String, val node: JsonNode) {
        val tags get() = node.path("tags").toList().map { it.asText() }
        override fun toString() = "${method.uppercase()} $path"
    }

    private fun operations() = api["paths"].properties().flatMap { (path, item) ->
        METHODS.mapNotNull { method -> item[method]?.let { Op(method, path, it) } }
    }

    /** 태그 검사 대상. 테스트 전용 관리자 탐침(AdminProbeConfig)은 실제 API가 아니라 뺀다(관리자 웹 추출도 뺀다). */
    private fun productOperations() = operations().filterNot { it.path.startsWith(TEST_PROBE) }

    @Test fun `모든 오퍼레이션은 이름을 붙인 태그를 가지고 컨트롤러 기본 이름 태그는 없다`() {
        val ops = productOperations()
        assertThat(ops).isNotEmpty
        assertThat(ops.filter { it.tags.isEmpty() }).describedAs("태그 없는 오퍼레이션").isEmpty()
        assertThat(ops.flatMap { it.tags }.filter { it.endsWith("-controller") }.distinct()).describedAs("기본 이름 태그").isEmpty()
        assertThat(ops.flatMap { it.tags }).describedAs("User는 Users로 합쳤다").doesNotContain("User")
        // 상단 태그 목록에 모든 태그의 설명이 있다(Swagger UI 묶음 설명).
        val described = api["tags"].toList().associate { it["name"].asText() to it.path("description").asText() }
        for (tag in ops.flatMap { it.tags }.toSet()) {
            assertThat(described[tag]).describedAs("$tag 설명").isNotBlank()
        }
    }

    @Test fun `관리자 경로는 Admin 태그만 앱 경로는 Admin 아닌 태그만 쓴다`() {
        for (op in productOperations()) {
            val admin = op.path.startsWith("/api/v1/admin/")
            assertThat(op.tags).describedAs(op.toString()).allSatisfy { tag ->
                assertThat(tag.startsWith("Admin ")).isEqualTo(admin)
            }
        }
    }

    @Test fun `상단 태그 목록은 앱 태그를 이름순으로 두고 관리자 태그를 그 뒤에 이름순으로 둔다`() {
        val listed = api["tags"].toList().map { it["name"].asText() }
        // Swagger UI는 이 목록 순서로 묶음을 보여 준다. 목록에 빠진 태그는 순서가 안 먹으므로 모두 있어야 한다.
        assertThat(listed).describedAs("상단 태그 목록").containsAll(productOperations().flatMap { it.tags }.toSet())
        val (admin, app) = listed.partition { it.startsWith("Admin ") }
        assertThat(app).isNotEmpty.isSorted
        assertThat(admin).isNotEmpty.isSorted
        assertThat(listed).containsExactlyElementsOf(app + admin)
    }

    @Test fun `servers는 상대 주소 하나다`() {
        val servers = api["servers"].toList()
        assertThat(servers).hasSize(1)
        assertThat(servers.single()["url"].asText()).isEqualTo("/")
    }

    @Test fun `operationId는 태그 정리 전 목록과 같다`() {
        val expected = javaClass.getResource("/openapi/operation-ids.txt")!!.readText().lines().filter { it.isNotBlank() }
        val actual = operations().map { "$it ${it.node["operationId"].asText()}" }.sorted()
        assertThat(actual).containsExactlyElementsOf(expected)
    }

    companion object {
        private val METHODS = listOf("get", "post", "put", "patch", "delete")
        private const val TEST_PROBE = "/api/v1/admin/foundation-probe"
    }
}
