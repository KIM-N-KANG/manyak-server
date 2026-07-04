package com.knk.manyak.story.controller

import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryDeleteControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `스토리를 삭제하면 204와 함께 deletedAt이 기록되는 소프트 삭제다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))
        // 자식 설정은 소프트 삭제 후에도 보존되어야 한다
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(story = story, name = "시작 설정", prologue = "프롤로그"),
        )

        restTestClient.delete()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isNoContent

        // 행은 남아 있고 deletedAt만 세팅된다 (물리 삭제 아님)
        val reloaded = storyRepository.findById(story.id).orElseThrow()
        assertThat(reloaded.deletedAt).isNotNull()
        // 자식 설정은 보존된다
        assertThat(storyStartSettingRepository.findById(startSetting.id)).isPresent
    }

    @Test
    fun `삭제한 스토리는 상세 조회에서 404로 제외된다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))

        restTestClient.delete()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isNoContent

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")
    }

    @Test
    fun `삭제한 스토리는 목록(batch) 조회에서 제외된다`() {
        val kept = storyRepository.save(Story(title = "보존 스토리"))
        val deleted = storyRepository.save(Story(title = "삭제 대상 스토리"))

        restTestClient.delete()
            .uri("/api/v1/stories/${deleted.publicId}")
            .exchange()
            .expectStatus().isNoContent

        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["${kept.publicId}", "${deleted.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].id").isEqualTo(kept.publicId.toString())
    }

    @Test
    fun `이미 삭제된 스토리를 다시 삭제하면 404로 응답한다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))

        restTestClient.delete().uri("/api/v1/stories/${story.publicId}").exchange().expectStatus().isNoContent

        restTestClient.delete()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")
    }

    @Test
    fun `존재하지 않는 ID를 삭제하면 404로 응답한다`() {
        restTestClient.delete()
            .uri("/api/v1/stories/999999")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/999999")
    }

    @Test
    fun `순차 PK(내부 id)로는 삭제되지 않고 404로 통일된다 (IDOR 차단)`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))

        // 내부 순차 PK 추측 삭제는 차단된다.
        restTestClient.delete()
            .uri("/api/v1/stories/${story.id}")
            .exchange()
            .expectStatus().isNotFound

        // 삭제되지 않았다.
        assertThat(storyRepository.findById(story.id).orElseThrow().deletedAt).isNull()
    }

    @Test
    fun `삭제는 다른 스토리에 영향을 주지 않는다`() {
        val target = storyRepository.save(Story(title = "삭제 대상 스토리"))
        val other = storyRepository.save(Story(title = "보존 스토리"))

        restTestClient.delete().uri("/api/v1/stories/${target.publicId}").exchange().expectStatus().isNoContent

        // 다른 스토리는 그대로 조회된다
        restTestClient.get()
            .uri("/api/v1/stories/${other.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(other.publicId.toString())

        val reloadedOther = storyRepository.findById(other.id).orElseThrow()
        assertThat(reloadedOther.deletedAt).isNull()
    }
}
