package com.knk.manyak.chat.controller

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryPlaySession
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatDeleteControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyPlaySessionRepository: StoryPlaySessionRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var storyChoiceRepository: StoryChoiceRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @BeforeEach
    fun setUp() {
        // 공유 H2(스프링 컨텍스트 캐시)에서 다른 테스트 클래스가 남긴 스토리 자식 행까지 FK 순서로 정리한다.
        // 자식(start_settings·settings 등)이 남아 있으면 stories 삭제가 FK 위반으로 실패한다.
        storyChoiceRepository.deleteAllInBatch()
        storyMessageRepository.deleteAllInBatch()
        storyPlaySessionRepository.deleteAllInBatch()
        storySuggestedInputRepository.deleteAllInBatch()
        storyStartSettingRepository.deleteAllInBatch()
        storySettingRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
    }

    @Test
    fun `채팅을 삭제하면 204와 함께 deletedAt이 기록되는 소프트 삭제다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))
        // 자식 메시지는 소프트 삭제 후에도 보존되어야 한다
        storyMessageRepository.save(
            StoryMessage(playSessionId = session.id, role = MessageRole.USER, content = "손을 올린다.", messageOrder = 1),
        )

        restTestClient.delete()
            .uri("/api/v1/chats/${session.publicId}")
            .exchange()
            .expectStatus().isNoContent

        // 행은 남아 있고 deletedAt만 세팅된다 (물리 삭제 아님)
        val reloaded = storyPlaySessionRepository.findById(session.id).orElseThrow()
        assertThat(reloaded.deletedAt).isNotNull()
        // 자식 메시지는 보존된다
        assertThat(storyMessageRepository.findByPlaySessionIdOrderByMessageOrderAsc(session.id)).hasSize(1)
    }

    @Test
    fun `삭제한 채팅은 상세 조회에서 404로 제외된다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        restTestClient.delete()
            .uri("/api/v1/chats/${session.publicId}")
            .exchange()
            .expectStatus().isNoContent

        restTestClient.get()
            .uri("/api/v1/chats/${session.publicId}")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("채팅을 찾을 수 없습니다.")
    }

    @Test
    fun `삭제한 채팅은 목록(batch) 조회에서 제외된다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))
        val kept = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))
        val deleted = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        restTestClient.delete()
            .uri("/api/v1/chats/${deleted.publicId}")
            .exchange()
            .expectStatus().isNoContent

        restTestClient.post()
            .uri("/api/v1/chats/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"chatIds":["${kept.publicId}","${deleted.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].id").isEqualTo(kept.publicId.toString())
    }

    @Test
    fun `이미 삭제된 채팅을 다시 삭제하면 404로 응답한다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))
        val session = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        restTestClient.delete().uri("/api/v1/chats/${session.publicId}").exchange().expectStatus().isNoContent

        restTestClient.delete()
            .uri("/api/v1/chats/${session.publicId}")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("채팅을 찾을 수 없습니다.")
    }

    @Test
    fun `존재하지 않는 임의 UUID를 삭제하면 404로 응답한다`() {
        val missing = UUID.randomUUID()
        restTestClient.delete()
            .uri("/api/v1/chats/$missing")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("채팅을 찾을 수 없습니다.")
    }

    @Test
    fun `순차 정수 ID로 삭제하면 404로 응답한다`() {
        // IDOR 방지: 공개 UUID가 아닌 순차 정수로는 타인의 채팅을 삭제할 수 없다.
        restTestClient.delete()
            .uri("/api/v1/chats/999999")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("채팅을 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/chats/999999")
    }

    @Test
    fun `삭제는 같은 스토리의 다른 채팅에 영향을 주지 않는다`() {
        val story = storyRepository.save(Story(title = "삭제 대상 스토리"))
        val target = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))
        val other = storyPlaySessionRepository.save(StoryPlaySession(storyId = story.id))

        restTestClient.delete().uri("/api/v1/chats/${target.publicId}").exchange().expectStatus().isNoContent

        // 다른 채팅은 그대로 조회된다
        restTestClient.get()
            .uri("/api/v1/chats/${other.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(other.publicId.toString())

        val reloadedOther = storyPlaySessionRepository.findById(other.id).orElseThrow()
        assertThat(reloadedOther.deletedAt).isNull()
    }
}
