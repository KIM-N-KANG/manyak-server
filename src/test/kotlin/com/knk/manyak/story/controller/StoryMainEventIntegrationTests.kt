package com.knk.manyak.story.controller

import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/**
 * GET·PUT /api/v1/stories/{storyId}/main-events 통합 검증(KNK-418, 스펙 §4-3-10).
 * - 교체 저장: 보낸 목록으로 전체 대체, 표시 순서는 배열 순서.
 * - 검증: 최대 10 초과·필드 누락 → 400. 스토리 없음 → 404.
 * - 노출: 스토리 상세(GET /stories/{id})에 mainEvents가 포함된다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryMainEventIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyMainEventRepository: StoryMainEventRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun seedStory(): String =
        storyRepository.save(Story(title = "테스트 스토리")).publicId.toString()

    private fun item(name: String, description: String = "설명", keySentence: String = "핵심 문장") =
        """{"name":"$name","description":"$description","keySentence":"$keySentence"}"""

    private fun putMainEvents(storyId: String, itemsJson: String) =
        restTestClient.put()
            .uri("/api/v1/stories/$storyId/main-events")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"mainEvents":[$itemsJson]}""")
            .exchange()

    private fun getMainEvents(storyId: String) =
        restTestClient.get().uri("/api/v1/stories/$storyId/main-events").exchange()

    @Test
    fun `주요 사건이 없으면 빈 배열을 반환한다`() {
        getMainEvents(seedStory())
            .expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `교체 저장하면 배열 순서로 저장하고 반환한다`() {
        val storyId = seedStory()

        putMainEvents(storyId, "${item("첫 사건")},${item("둘째 사건")}")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].name").isEqualTo("첫 사건")
            .jsonPath("$[0].sortOrder").isEqualTo(0)
            .jsonPath("$[1].name").isEqualTo("둘째 사건")
            .jsonPath("$[1].sortOrder").isEqualTo(1)

        getMainEvents(storyId)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].name").isEqualTo("첫 사건")
    }

    @Test
    fun `다시 교체 저장하면 기존을 대체한다`() {
        val storyId = seedStory()
        putMainEvents(storyId, "${item("옛 사건")},${item("옛 사건2")}").expectStatus().isOk

        putMainEvents(storyId, item("새 사건")).expectStatus().isOk

        getMainEvents(storyId)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].name").isEqualTo("새 사건")
            .jsonPath("$[0].sortOrder").isEqualTo(0)
    }

    @Test
    fun `빈 배열로 교체하면 전부 삭제된다`() {
        val storyId = seedStory()
        putMainEvents(storyId, item("사건")).expectStatus().isOk

        restTestClient.put()
            .uri("/api/v1/stories/$storyId/main-events")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"mainEvents":[]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.length()").isEqualTo(0)

        getMainEvents(storyId).expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `10개를 넘기면 400이다`() {
        val storyId = seedStory()
        val eleven = (1..11).joinToString(",") { item("사건$it") }

        putMainEvents(storyId, eleven).expectStatus().isBadRequest
    }

    @Test
    fun `이름이 비어 있으면 400이다`() {
        putMainEvents(seedStory(), item(name = "")).expectStatus().isBadRequest
    }

    @Test
    fun `없는 스토리에 조회하면 404다`() {
        getMainEvents(UUID.randomUUID().toString()).expectStatus().isNotFound
    }

    @Test
    fun `없는 스토리에 교체 저장하면 404다`() {
        putMainEvents(UUID.randomUUID().toString(), item("사건")).expectStatus().isNotFound
    }

    @Test
    fun `스토리 상세에 주요 사건이 포함된다`() {
        val storyId = seedStory()
        putMainEvents(storyId, item("상세 노출 사건")).expectStatus().isOk

        restTestClient.get().uri("/api/v1/stories/$storyId").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.mainEvents.length()").isEqualTo(1)
            .jsonPath("$.mainEvents[0].name").isEqualTo("상세 노출 사건")

        // 원장이 실제로 저장됐는지도 확인한다.
        val story = storyRepository.findByPublicIdAndDeletedAtIsNull(UUID.fromString(storyId))!!
        assertThat(storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(story.id)).hasSize(1)
    }
}
