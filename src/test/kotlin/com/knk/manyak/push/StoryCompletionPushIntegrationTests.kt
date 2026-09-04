package com.knk.manyak.push

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/**
 * 스토리 완성 푸시 발송(KNK-1115).
 *
 * - 스토리 완성(STORY_COMPLETION) 요청이 COMPLETED로 마킹되고 **커밋된 뒤** 제작자에게 서비스 알림을 보낸다.
 * - 스토리라인 생성 단계, 게스트 제작, 서비스 알림을 끈 회원(KNK-1132)은 대상이 아니다.
 * - 멱등 replay는 완성 마킹에 도달하지 않으므로 중복 발송이 없다.
 * - 발송 실패는 생성 결과를 되돌리지 않는다(푸시는 부가 기능).
 *
 * AI는 메인 코드의 스텁(`manyak.ai.story.stub`)을 켜 쓴다 — 가짜 빈을 중첩 @TestConfiguration으로 두면
 * 이 클래스만의 Spring 컨텍스트가 하나 더 생긴다(SpringContextBudgetGuardTests).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // 실제 AI 대신 메인 코드의 스텁을 켠다(StubStoryAiClient). 중첩 @TestConfiguration으로 가짜 빈을 두면
    // 이 클래스만의 Spring 컨텍스트가 하나 더 생긴다(SpringContextBudgetGuardTests).
    properties = ["manyak.ai.story.stub=true"],
)
class StoryCompletionPushIntegrationTests {

    @MockitoBean private lateinit var fcmPushSender: FcmPushSender

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var sessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var storylineRepository: StoryCreationStorylineRepository
    @Autowired private lateinit var tagRepository: StoryCreationTagRepository
    @Autowired private lateinit var requestRepository: StoryCreationRequestRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        reset(fcmPushSender)
    }

    private fun saveMember(servicePush: Boolean = true): User =
        userRepository.save(
            User(nickname = "제작자", status = UserStatus.ACTIVE, servicePushEnabled = servicePush),
        )

    private fun bearer(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    /** 완성 요청이 참조할 스토리라인을 심는다. [owner]가 null이면 게스트 세션이다. */
    private fun seedStoryline(owner: User?): StoryCreationStoryline {
        val session = sessionRepository.save(
            StoryCreationSession(userId = owner?.id, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        return storylineRepository.save(
            StoryCreationStoryline(creationSession = session, storylineText = STORYLINE_TEXT, storylineOrder = 1),
        )
    }

    private fun completeStory(
        requestId: UUID,
        storyline: StoryCreationStoryline,
        user: User?,
    ): RestTestClient.ResponseSpec {
        val spec = restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", DEVICE_ID)
            .contentType(MediaType.APPLICATION_JSON)
        user?.let { spec.header("Authorization", bearer(it)) }
        return spec
            .body(
                """{"requestId":"$requestId","simpleCreationId":${storyline.creationSession.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
    }

    @Test
    fun `회원의 스토리 완성은 제작자에게 완성 푸시를 한 번 보낸다`() {
        val member = saveMember()
        val storyline = seedStoryline(member)

        val body = completeStory(UUID.randomUUID(), storyline, member)
            .expectStatus().isCreated
            .expectBody()
            .returnResult()
        val storyId = STORY_ID_PATTERN.find(String(body.responseBody!!))!!.groupValues[1]

        // 값 그대로 검증한다 — Kotlin의 non-null 파라미터에 ArgumentCaptor.capture()가 null을 넘겨 NPE가 난다.
        verify(fcmPushSender).sendToUser(
            member.id,
            mapOf("type" to "STORY_COMPLETED", "storyId" to storyId, "title" to STORY_TITLE),
        )
    }

    @Test
    fun `서비스 알림을 끈 회원에게는 보내지 않는다`() {
        val member = saveMember(servicePush = false)
        val storyline = seedStoryline(member)

        completeStory(UUID.randomUUID(), storyline, member).expectStatus().isCreated

        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `게스트의 스토리 완성은 보낼 대상이 없어 발송하지 않는다`() {
        val storyline = seedStoryline(owner = null)

        completeStory(UUID.randomUUID(), storyline, user = null).expectStatus().isCreated

        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `스토리라인 생성 완료로는 발송하지 않는다`() {
        val member = saveMember()
        val genre = tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "판타지",
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = 1,
            ),
        )

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", DEVICE_ID)
            .header("Authorization", bearer(member))
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${UUID.randomUUID()}","genreTagIds":[${genre.id}],"protagonist":{}}""")
            .exchange()
            .expectStatus().isCreated

        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `같은 requestId 재요청(멱등 replay)은 추가로 발송하지 않는다`() {
        val member = saveMember()
        val storyline = seedStoryline(member)
        val requestId = UUID.randomUUID()

        completeStory(requestId, storyline, member).expectStatus().isCreated
        completeStory(requestId, storyline, member).expectStatus().isCreated

        // replay는 COMPLETED 마킹에 도달하지 않으므로 발송도 한 번뿐이다.
        verify(fcmPushSender).sendToUser(anyLong(), anyMap())
    }

    @Test
    fun `발송이 실패해도 요청은 COMPLETED로 남고 응답은 정상이다`() {
        val member = saveMember()
        val storyline = seedStoryline(member)
        val requestId = UUID.randomUUID()
        doThrow(IllegalStateException("FCM 다운")).`when`(fcmPushSender).sendToUser(anyLong(), anyMap())

        completeStory(requestId, storyline, member).expectStatus().isCreated

        assertThat(requestRepository.findByRequestId(requestId)!!.status)
            .isEqualTo(StoryCreationRequestStatus.COMPLETED)
    }

    companion object {
        private const val DEVICE_ID = "device-push-completion"
        private const val STORYLINE_TEXT = "예시 스토리라인"
        // 테스트 프로파일은 AI 스텁을 켜 둔다(manyak.ai.story.stub). 스텁의 제목 규칙이 곧 기대값이다.
        private const val STORY_TITLE = "[스텁] $STORYLINE_TEXT"
        private val STORY_ID_PATTERN = """"id":"([^"]+)"""".toRegex()
    }
}
