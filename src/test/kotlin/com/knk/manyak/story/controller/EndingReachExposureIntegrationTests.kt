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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
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
        userStoryEndingReachRepository.save(
            UserStoryEndingReach(
                userId = member.id,
                storyId = story.id,
                endingNameSnapshot = ending.name,
                endingId = ending.id,
            ),
        )

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
    fun `엔딩을 같은 이름으로 교체해도 과거 도달 기록이 상세에 남는다`() {
        userStoryEndingReachRepository.save(
            UserStoryEndingReach(
                userId = member.id,
                storyId = story.id,
                endingNameSnapshot = ending.name,
                endingId = ending.id,
            ),
        )

        // 수정 API의 endings[] 전체 교체와 같은 결과: 행을 지우고 같은 이름으로 새로 만든다.
        // 예전에는 FK가 ON DELETE CASCADE라 이 순간 집계 행이 통째로 삭제됐다(V70 이전).
        val startSetting = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()
        jdbcTemplate.update("DELETE FROM story_endings WHERE id = ?", ending.id)
        val recreated = storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "해피", minTurns = 1, achievementCondition = "이긴다", epilogue = "평화", sortOrder = 1),
        )
        assertThat(recreated.id).isNotEqualTo(ending.id) // 값이 갈라졌는지 확인

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reachedEndings.length()").isEqualTo(1)
            .jsonPath("$.reachedEndings[0]").isEqualTo("해피")
    }

    @Test
    fun `엔딩 id 없이 이름만 기록된 도달도 상세에 노출된다`() {
        // 비공개 상태에서 엔딩이 교체된 뒤 도달하면 FK를 만족시킬 id가 없다. 그래도 집계에 남아야 한다.
        userStoryEndingReachRepository.save(
            UserStoryEndingReach(
                userId = member.id,
                storyId = story.id,
                endingNameSnapshot = "해피",
                endingId = null,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reachedEndings.length()").isEqualTo(1)
            .jsonPath("$.reachedEndings[0]").isEqualTo("해피")
    }

    @Test
    fun `스토리에 없는 이름으로 기록된 도달도 목록에서 빠지지 않는다`() {
        // 제작자가 엔딩을 아예 다른 이름으로 갈아치운 경우. 순서를 알 수 없으니 뒤에 붙되 사라지지는 않는다.
        userStoryEndingReachRepository.save(
            UserStoryEndingReach(userId = member.id, storyId = story.id, endingNameSnapshot = "사라진 엔딩", endingId = null),
        )
        userStoryEndingReachRepository.save(
            UserStoryEndingReach(userId = member.id, storyId = story.id, endingNameSnapshot = "해피", endingId = ending.id),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reachedEndings.length()").isEqualTo(2)
            // 현재 스토리에 있는 이름이 먼저, 사라진 이름이 뒤로.
            .jsonPath("$.reachedEndings[0]").isEqualTo("해피")
            .jsonPath("$.reachedEndings[1]").isEqualTo("사라진 엔딩")
    }

    @Test
    fun `동명 엔딩이 있으면 표시 순서는 첫 등장 자리를 따른다`() {
        // 시작 설정 A의 두 번째 엔딩과 시작 설정 B의 첫 엔딩이 같은 이름이다. associate는 마지막 값을 남겨
        // B의 인덱스가 쓰였는데, 첫 등장(A의 두 번째)을 지켜야 '해피'가 '슬픔'보다 앞에 온다.
        val startSettingA = storyStartSettingRepository.findAllByStoryIdOrderByIdAsc(story.id).single()
        storyEndingRepository.save(
            StoryEnding(startSetting = startSettingA, name = "슬픔", minTurns = 1, achievementCondition = "c", epilogue = "e", sortOrder = 2),
        )
        val startSettingB = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "B"))
        storyEndingRepository.save(
            StoryEnding(startSetting = startSettingB, name = "해피", minTurns = 1, achievementCondition = "c", epilogue = "e", sortOrder = 1),
        )
        userStoryEndingReachRepository.save(
            UserStoryEndingReach(userId = member.id, storyId = story.id, endingNameSnapshot = "슬픔", endingId = null),
        )
        userStoryEndingReachRepository.save(
            UserStoryEndingReach(userId = member.id, storyId = story.id, endingNameSnapshot = "해피", endingId = ending.id),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // A: [해피(0), 슬픔(1)], B: [해피(2)] → 첫 등장 기준이면 해피(0) < 슬픔(1).
            .jsonPath("$.reachedEndings[0]").isEqualTo("해피")
            .jsonPath("$.reachedEndings[1]").isEqualTo("슬픔")
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
