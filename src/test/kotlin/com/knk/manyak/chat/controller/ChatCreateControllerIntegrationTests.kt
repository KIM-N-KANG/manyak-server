package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
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
import java.time.Instant

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatCreateControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `채팅을 생성하면 201과 함께 ACTIVE 플레이 세션이 만들어진다`() {
        val story = storyRepository.save(
            Story(
                title = "호아킨 아카데미의 무속성 신입생",
                oneLineIntro = "속성을 잃은 신입생의 첫날.",
                description = "마법 아카데미 입학 적성 검사 이야기.",
                genre = "판타지",
            ),
        )
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "입학 적성 검사",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사 직전의 검사장.",
            ),
        )
        // 추천 입력은 input_order로 순서가 정해지며, 응답도 그 순서를 보존해야 한다.
        storySuggestedInputRepository.save(
            StorySuggestedInput(startSetting = startSetting, inputText = "마법수정에 손을 올린다.", inputOrder = 2),
        )
        storySuggestedInputRepository.save(
            StorySuggestedInput(startSetting = startSetting, inputText = "검사장을 둘러본다.", inputOrder = 1),
        )

        val response = restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(CreateChatResponse::class.java)
            .returnResult()
            .responseBody!!

        // 응답 storyId는 순차 PK가 아니라 스토리 공개 식별자(public_id)다.
        assertThat(response.storyId).isEqualTo(story.publicId.toString())
        assertThat(response.prologue).isEqualTo("마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.")
        assertThat(response.suggestedInputs).containsExactly("검사장을 둘러본다.", "마법수정에 손을 올린다.")
        assertThat(response.createdAt).isNotNull()

        val sessions = storyChatRepository.findAll()
        assertThat(sessions).hasSize(1)
        val session = sessions.first()
        // 응답 id는 순차 PK가 아니라 추측 불가능한 공개 식별자(publicId)다 (IDOR 방지)
        assertThat(response.id).isEqualTo(session.publicId.toString())
        assertThat(response.id).isNotEqualTo(session.id.toString())
        // 세션의 내부 FK(storyId)는 스토리 내부 PK로 저장된다.
        assertThat(session.storyId).isEqualTo(story.id)
        assertThat(session.startSettingId).isEqualTo(startSetting.id)
        assertThat(session.status).isEqualTo(ChatStatus.ACTIVE)
        assertThat(session.userId).isNull()

        // 오프닝 메시지는 저장하지 않는다 (prologue는 start_setting이 단일 출처)
        assertThat(storyMessageRepository.count()).isZero()
    }

    @Test
    fun `시작 설정이 없는 스토리도 빈 프롤로그와 빈 추천 입력으로 채팅이 생성된다`() {
        val story = storyRepository.save(
            Story(title = "설정 미완 스토리"),
        )

        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.storyId").isEqualTo(story.publicId.toString())
            .jsonPath("$.prologue").isEqualTo("")
            .jsonPath("$.suggestedInputs").isArray()
            .jsonPath("$.suggestedInputs").isEmpty()

        val session = storyChatRepository.findAll().first()
        assertThat(session.startSettingId).isNull()
    }

    @Test
    fun `시작 설정은 있으나 추천 입력이 없으면 빈 추천 입력으로 채팅이 생성된다`() {
        val story = storyRepository.save(
            Story(title = "추천 입력 미등록 스토리"),
        )
        storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "시작 장면",
                prologue = "이야기가 시작된다.",
                startSituation = "막이 오른다.",
            ),
        )

        val response = restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(CreateChatResponse::class.java)
            .returnResult()
            .responseBody!!

        assertThat(response.prologue).isEqualTo("이야기가 시작된다.")
        assertThat(response.suggestedInputs).isEmpty()
    }

    @Test
    fun `존재하지 않는 스토리로 채팅을 생성하면 404로 응답한다`() {
        val missing = java.util.UUID.randomUUID()
        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"$missing"}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/chats")

        assertThat(storyChatRepository.count()).isZero()
    }

    @Test
    fun `순차 정수나 비-UUID storyId로 채팅을 생성하면 404로 통일된다 (IDOR 차단)`() {
        // 스토리 식별자는 공개 UUID이므로 순차 정수를 추측해도 채팅을 시작할 수 없다.
        val story = storyRepository.save(Story(title = "공개 식별자만 노출하는 스토리"))

        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.id}"}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")

        assertThat(storyChatRepository.count()).isZero()
    }

    @Test
    fun `소프트 삭제된 스토리로 채팅을 생성하면 404로 응답한다 (KNK-257)`() {
        val story = storyRepository.save(Story(title = "삭제될 스토리"))
        // 소프트 삭제 후에도 시작 설정(자식 행)은 보존된다. 그럼에도 채팅 생성은 거부되어야 한다.
        storyStartSettingRepository.save(
            StoryStartSetting(story = story, name = "시작 장면", prologue = "이야기가 시작된다."),
        )
        story.deletedAt = Instant.now()
        storyRepository.save(story)

        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")

        assertThat(storyChatRepository.count()).isZero()
    }

    // ---- 소유권 게이트(§4-5, KNK-480): 회원-게스트 교차 접근 ----

    @Test
    fun `회원이 게스트(NULL) 소유 스토리로 채팅을 생성하면 403이다`() {
        // 교차 접근 차단: 인증 회원은 게스트가 만든 NULL 소유 스토리에 채팅을 시작할 수 없다(이관 후 접근).
        val member = saveUser("회원")
        val story = storyRepository.save(Story(title = "게스트 스토리")) // userId null, 기본 PUBLISHED·PUBLIC

        restTestClient.post()
            .uri("/api/v1/chats")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isForbidden

        assertThat(storyChatRepository.count()).isZero()
    }

    @Test
    fun `회원이 본인 소유 스토리로 채팅을 생성하면 201이다`() {
        // 소유자 있는 스토리 채팅 생성은 교차 접근 차단의 영향을 받지 않는다(회귀 가드).
        val member = saveUser("작가")
        val story = storyRepository.save(Story(title = "내 스토리", userId = member.id))

        restTestClient.post()
            .uri("/api/v1/chats")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated
    }

    @Test
    fun `회원이 공개 발행된 타인 스토리로 채팅을 생성하면 201이다`() {
        // 소유자가 있는 공개 스토리는 다른 회원도 채팅을 시작할 수 있다(공개 플레이 유지).
        val author = saveUser("작가")
        val reader = saveUser("독자")
        val story = storyRepository.save(Story(title = "공개작", userId = author.id)) // 기본 PUBLISHED·PUBLIC

        restTestClient.post()
            .uri("/api/v1/chats")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(reader.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated
    }

    private fun saveUser(nickname: String): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))
}
