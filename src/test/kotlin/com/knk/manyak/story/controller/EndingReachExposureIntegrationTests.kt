package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.entity.UserStoryEndingReach
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * KNK-523(B5-D): 엔딩 도달 이력 노출·집계·이관.
 *
 * - GET /stories/{id}의 reachedEndings는 요청 회원 집계(게스트 빈 배열).
 * - POST /chats/batch 카드의 reachedEndings는 채팅 도달 엔딩(이름).
 * - POST /auth/migrate로 이관한 게스트 채팅의 도달분이 회원 집계에 백필된다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndingReachExposureIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyStartSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var storyEndingRepository: StoryEndingRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var userStoryEndingReachRepository: UserStoryEndingReachRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private lateinit var member: User
    private lateinit var story: Story
    private lateinit var ending: StoryEnding

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        member = userRepository.save(User(nickname = "회원", status = UserStatus.ACTIVE))
        // 회원 소유 + 공개(PUBLISHED·PUBLIC) 스토리라 회원·게스트 모두 상세를 읽을 수 있다.
        story = storyRepository.save(
            Story(title = "도달 노출 스토리", userId = member.id, status = StoryStatus.PUBLISHED, visibility = StoryVisibility.PUBLIC),
        )
        val startSetting = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "시작"))
        ending = storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "해피", minTurns = 1, achievementCondition = "이긴다", epilogue = "평화", sortOrder = 1),
        )
    }

    @Test
    fun `스토리 상세의 reachedEndings는 회원 집계이고 게스트는 빈 배열이다`() {
        userStoryEndingReachRepository.save(UserStoryEndingReach(userId = member.id, storyId = story.id, endingId = ending.id))

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reachedEndings.length()").isEqualTo(1)
            .jsonPath("$.reachedEndings[0]").isEqualTo("해피")

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reachedEndings.length()").isEqualTo(0)
    }

    @Test
    fun `채팅 카드의 reachedEndings는 그 채팅이 도달한 엔딩 이름이다`() {
        val chat = storyChatRepository.save(
            StoryChat(storyId = story.id, userId = member.id, reachedEndingId = ending.id),
        )

        restTestClient.post()
            .uri("/api/v1/chats/batch")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"chatIds":["${chat.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].reachedEndings[0]").isEqualTo("해피")
    }

    @Test
    fun `게스트 채팅 이관 시 도달분이 회원 집계에 백필된다`() {
        // 게스트(userId null) 채팅이 엔딩에 도달한 상태.
        val guestChat = storyChatRepository.save(
            StoryChat(storyId = story.id, userId = null, reachedEndingId = ending.id),
        )

        restTestClient.post()
            .uri("/api/v1/auth/migrate")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":[],"chatIds":["${guestChat.publicId}"]}""")
            .exchange()
            .expectStatus().isOk

        val reaches = userStoryEndingReachRepository.findByUserIdAndStoryId(member.id, story.id)
        org.assertj.core.api.Assertions.assertThat(reaches.map { it.endingId }).containsExactly(ending.id)
    }
}
