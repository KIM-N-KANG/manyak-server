package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.client.AiResponseMeta
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryItem
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.ParentLinkError
import com.knk.manyak.story.entity.StoryCreationRequest
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * KNK-755: 스토리라인 재생성 체인(`parent_creation_id`)의 쓰기 검증.
 *
 * 프론트가 보낸 부모 creation_id를 그대로 헤더로 흘리면 AI 파이프라인이 존재하지 않거나 남의 여정을 체인으로 신뢰하게 된다.
 * 그래서 서버가 존재·자기참조·소유 연속성을 검증해 **통과한 값만** 헤더로 내보내고, 실패해도 400이 아니라
 * 시도값(`attempted_parent_creation_id`)과 사유(`parent_link_error`)를 남긴다 —
 * 그래야 "최초 생성(부모 없음)"과 "재생성인데 연결 실패"가 DB에서 구분된다.
 */
/** 스토리라인 호출에 실린 [AiTraceLink]를 붙잡아 두는 가짜 AI 클라이언트. AI 서버 없이 헤더 재료를 검증한다. */
class ParentChainCapturingStoryAiClient : StoryAiClient {
    val storylineLink = AtomicReference<AiTraceLink>()

    override fun createStorylines(request: AiStorylinesRequest, traceLink: AiTraceLink): AiStorylinesResponse {
        storylineLink.set(traceLink)
        return AiStorylinesResponse(
            stories = (1..3).map { AiStoryItem(id = it, storyline = "스토리라인 $it", recommendedInfos = listOf("정보 $it")) },
            meta = AiResponseMeta(),
        )
    }

    override fun compileStory(request: AiStoryCompileRequest, traceLink: AiTraceLink): AiStoryCompileResponse =
        AiStoryCompileResponse(
            stories = AiStoryMeta("생성된 스토리", "한 줄 소개", "설명"),
            storySettings = AiStorySettings("세계관", "캐릭터", "역할", "규칙"),
            storyStartSettings = AiStoryStartSettings("시작", "상황", "프롤로그"),
            storySuggestedInputs = listOf("추천1"),
            meta = AiResponseMeta(),
        )
}

/**
 * 중첩이 아니라 **최상위** 설정이다(SpringContextBudgetGuardTests): 중첩 @TestConfiguration은 클래스마다 컨텍스트를
 * 하나씩 늘려 캐시 축출을 부르므로, 여러 클래스가 @Import로 공유할 수 있는 최상위 설정으로 둔다.
 */
@TestConfiguration
class ParentChainCapturingAiClientConfig {
    @Bean
    @Primary
    fun parentChainCapturingStoryAiClient(): ParentChainCapturingStoryAiClient = ParentChainCapturingStoryAiClient()
}

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ParentChainCapturingAiClientConfig::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-parent-chain;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class SimpleStoryCreationParentChainIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyAiClient: ParentChainCapturingStoryAiClient
    @Autowired private lateinit var tagRepository: StoryCreationTagRepository
    @Autowired private lateinit var creationRequestRepository: StoryCreationRequestRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private var tagId: Long = 0

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        tagId = savePredefinedGenreTag().id
    }

    @Test
    fun `같은 소유자의 부모는 검증을 통과해 저장되고 헤더로 나간다`() {
        val parentId = postStorylines(UUID.randomUUID())
        val childId = UUID.randomUUID()

        postStorylines(childId, parentCreationId = parentId)

        assertThat(storyAiClient.storylineLink.get().parentCreationId)
            .`as`("검증을 통과한 부모는 헤더로 forward된다")
            .isEqualTo(parentId)
        val row = rowOf(childId)
        assertThat(row.parentRequestId).isEqualTo(parentId)
        assertThat(row.attemptedParentCreationId).isEqualTo(parentId)
        assertThat(row.parentLinkError).isNull()
    }

    @Test
    fun `존재하지 않는 부모는 NOT_FOUND로 남고 헤더는 생략되며 요청은 성공한다`() {
        val childId = UUID.randomUUID()
        val missingParent = UUID.randomUUID()

        // 400이 아니라 201 — 관측이 비즈니스를 막지 않는다.
        postStorylines(childId, parentCreationId = missingParent)

        assertThat(storyAiClient.storylineLink.get().parentCreationId).isNull()
        val row = rowOf(childId)
        assertThat(row.parentRequestId).isNull()
        assertThat(row.attemptedParentCreationId).isEqualTo(missingParent)
        assertThat(row.parentLinkError).isEqualTo(ParentLinkError.NOT_FOUND)
    }

    @Test
    fun `게스트 부모를 다른 기기가 참조하면 OWNER_MISMATCH다`() {
        val parentId = postStorylines(UUID.randomUUID(), deviceId = "device-a")
        val childId = UUID.randomUUID()

        postStorylines(childId, deviceId = "device-b", parentCreationId = parentId)

        assertRejected(childId, parentId, ParentLinkError.OWNER_MISMATCH)
    }

    @Test
    fun `다른 회원이 다른 기기에서 참조하면 OWNER_MISMATCH다`() {
        val parentId = postStorylines(UUID.randomUUID(), deviceId = "device-a", authorization = tokenOfNewMember())
        val childId = UUID.randomUUID()

        postStorylines(childId, deviceId = "device-b", authorization = tokenOfNewMember(), parentCreationId = parentId)

        assertRejected(childId, parentId, ParentLinkError.OWNER_MISMATCH)
    }

    @Test
    fun `같은 기기라도 계정이 다르면 OWNER_MISMATCH다`() {
        // 이번 설계 강화의 핵심: 양쪽 다 회원이면 user_id 엄격 일치만 인정하고 device_id_hash로 보조 판정하지 않는다.
        // 계정 A 로그아웃 후 계정 B로 재생성하면 기기 해시는 같아도 서로 다른 계정의 여정이다.
        val sharedDevice = "shared-device"
        val parentId = postStorylines(UUID.randomUUID(), deviceId = sharedDevice, authorization = tokenOfNewMember())
        val childId = UUID.randomUUID()

        postStorylines(childId, deviceId = sharedDevice, authorization = tokenOfNewMember(), parentCreationId = parentId)

        assertRejected(childId, parentId, ParentLinkError.OWNER_MISMATCH)
    }

    @Test
    fun `자기 자신을 부모로 지정하면 SELF_REFERENCE다`() {
        val childId = UUID.randomUUID()

        postStorylines(childId, parentCreationId = childId)

        assertRejected(childId, childId, ParentLinkError.SELF_REFERENCE)
    }

    @Test
    fun `게스트로 만든 부모는 같은 기기에서 로그인한 회원이 이어받는다`() {
        // 회원 요청에도 device_id_hash가 저장되므로, 게스트→회원 전환 중에도 같은 기기면 연속으로 인정한다.
        val sharedDevice = "handoff-device"
        val parentId = postStorylines(UUID.randomUUID(), deviceId = sharedDevice)
        val childId = UUID.randomUUID()

        postStorylines(childId, deviceId = sharedDevice, authorization = tokenOfNewMember(), parentCreationId = parentId)

        assertThat(storyAiClient.storylineLink.get().parentCreationId).isEqualTo(parentId)
        val row = rowOf(childId)
        assertThat(row.parentRequestId).isEqualTo(parentId)
        assertThat(row.parentLinkError).isNull()
    }

    @Test
    fun `최초 생성과 연결 실패는 DB에서 구분된다`() {
        val firstId = UUID.randomUUID()
        val failedId = UUID.randomUUID()
        val missingParent = UUID.randomUUID()

        postStorylines(firstId)
        postStorylines(failedId, parentCreationId = missingParent)

        // 최초 생성: 세 컬럼 전부 NULL — 시도 자체가 없었다.
        val first = rowOf(firstId)
        assertThat(first.parentRequestId).isNull()
        assertThat(first.attemptedParentCreationId).isNull()
        assertThat(first.parentLinkError).isNull()
        // 연결 실패: parent_request_id는 NULL이지만 시도값·사유가 남아 최초 생성으로 오판되지 않는다.
        val failed = rowOf(failedId)
        assertThat(failed.parentRequestId).isNull()
        assertThat(failed.attemptedParentCreationId).isNotNull()
        assertThat(failed.parentLinkError).isNotNull()
    }

    private fun assertRejected(childId: UUID, attempted: UUID, expected: ParentLinkError) {
        assertThat(storyAiClient.storylineLink.get().parentCreationId)
            .`as`("검증에 실패한 부모는 헤더로 나가지 않는다")
            .isNull()
        val row = rowOf(childId)
        assertThat(row.parentRequestId).isNull()
        assertThat(row.attemptedParentCreationId).isEqualTo(attempted)
        assertThat(row.parentLinkError).isEqualTo(expected)
    }

    private fun rowOf(requestId: UUID): StoryCreationRequest =
        creationRequestRepository.findByRequestId(requestId) ?: error("요청 행이 없습니다: $requestId")

    private fun tokenOfNewMember(): String {
        val member = userRepository.save(User(nickname = "회원${UUID.randomUUID()}", status = UserStatus.ACTIVE))
        return "Bearer ${jwtTokenProvider.issueAccessToken(member.publicId)}"
    }

    private fun savePredefinedGenreTag(): StoryCreationTag =
        tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "판타지",
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = 0,
                isActive = true,
            ),
        )

    /** 스토리라인 생성을 호출하고 그 요청의 creation_id(=requestId)를 돌려준다. */
    private fun postStorylines(
        requestId: UUID,
        deviceId: String = "test-device",
        authorization: String? = null,
        parentCreationId: UUID? = null,
    ): UUID {
        val parentField = parentCreationId?.let { ""","isRegenerated":true,"parentCreationId":"$it"""" }.orEmpty()
        val spec = restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", deviceId)
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header("Authorization", it) }
        spec.body("""{"requestId":"$requestId","selectedTagIds":[$tagId]$parentField}""")
            .exchange()
            .expectStatus().isCreated
        return requestId
    }
}
