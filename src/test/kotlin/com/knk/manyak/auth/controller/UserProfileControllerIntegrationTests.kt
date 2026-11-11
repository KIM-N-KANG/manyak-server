package com.knk.manyak.auth.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.dto.UpdateProfileRequest
import com.knk.manyak.auth.event.UserNicknameChangedEvent
import com.knk.manyak.auth.service.ProfileUpdater
import com.knk.manyak.auth.social.ProfileImagePresetService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.service.StorySearchDocumentReader
import com.knk.manyak.search.service.StorySearchIndexAdmin
import com.knk.manyak.search.service.StorySearchIndexer
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.MockedBoundaryIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.core.IndexRequest
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient
import org.opensearch.client.transport.endpoints.BooleanResponse
import org.opensearch.client.util.ObjectBuilder
import java.time.Instant
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 프로필 수정 API 통합 검증(KNK-1147, 정책 KNK-1146).
 * - 닉네임은 2~20자, 한글 완성형·영문·숫자만 허용하고 공백은 거부한다.
 * - 유일성은 정규화 키(소문자 + 공백 제거) 기준이라, 기존 공백 포함 값도 유일 판정에 반영한다.
 * - 프로필 이미지는 프리셋 선택만 지원한다(업로드 없음). 닉네임 변경과 독립이다.
 * - 인증 필수이며 정지 계정은 403이다.
 */
class UserProfileControllerIntegrationTests : MockedBoundaryIntegrationTest() {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var profileImagePresetService: ProfileImagePresetService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var indexer: StorySearchIndexer
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var profileUpdater: ProfileUpdater
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var searchClient: OpenSearchClient
    @Autowired private lateinit var searchProperties: StorySearchProperties
    @Autowired private lateinit var documentReader: StorySearchDocumentReader

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(
        nickname: String = "몽환적인 이야기꾼",
        status: UserStatus = UserStatus.ACTIVE,
    ): User = userRepository.save(
        User(
            nickname = nickname,
            status = status,
            profileImageUrl = "https://api.manyak.app/profile-presets/%EC%9D%B4%EC%95%BC%EA%B8%B0%EA%BE%BC.png",
            profileThumbnailBase64 = "seed-thumb",
        ),
    )

    private fun bearer(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    private fun patch(user: User, body: String) =
        restTestClient.patch()
            .uri(PATH)
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()

    private fun reload(user: User): User = userRepository.findById(user.id).orElseThrow()

    @Test
    fun `사용자 없는 유효 토큰으로 프리셋을 조회하면 401이다`() {
        val token = jwtTokenProvider.issueAccessToken(java.util.UUID.randomUUID())
        restTestClient.get().uri(PRESETS_PATH)
            .header("Authorization", "Bearer $token")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `토큰 없이 수정하거나 프리셋을 조회하면 401이다`() {
        restTestClient.patch()
            .uri(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"nickname":"새 닉네임"}""")
            .exchange()
            .expectStatus().isUnauthorized

        restTestClient.get().uri(PRESETS_PATH).exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `닉네임만 바꾸면 프로필 이미지는 그대로다`() {
        val user = saveUser()

        patch(user, """{"nickname":"새로운작가"}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nickname").isEqualTo("새로운작가")
            .jsonPath("$.profileImageUrl").isNotEmpty

        val reloaded = reload(user)
        assertThat(reloaded.nickname).isEqualTo("새로운작가")
        assertThat(reloaded.profileThumbnailBase64).isEqualTo("seed-thumb")
    }

    @Test
    fun `프로필 수정 응답도 크레딧 잔액과 오늘 출석 여부를 담는다`() {
        val user = saveUser()
        patch(user, """{"nickname":"잔액확인작가"}""")
            .expectStatus().isOk.expectBody()
            .jsonPath("$.creditBalance").isEqualTo(0)
            .jsonPath("$.attendedToday").isEqualTo(false)

        creditWalletService.reward(user.id, 300, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        val attendance = restTestClient.post().uri("/api/v1/users/me/credits/attendance").header("Authorization", bearer(user))
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!!
        val expectedBalance = 300 + (attendance["amount"] as Number).toLong()

        patch(user, """{"nickname":"출석확인작가"}""")
            .expectStatus().isOk.expectBody()
            .jsonPath("$.creditBalance").value<Number> { assertThat(it.toLong()).isEqualTo(expectedBalance) }
            .jsonPath("$.attendedToday").isEqualTo(true)
    }

    @Test
    fun `프리셋만 바꾸면 닉네임은 그대로다`() {
        val user = saveUser()

        patch(user, """{"profileImagePreset":"마법사"}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nickname").isEqualTo("몽환적인 이야기꾼")

        val reloaded = reload(user)
        assertThat(reloaded.nickname).isEqualTo("몽환적인 이야기꾼")
        assertThat(reloaded.profileImageUrl).isEqualTo(profileImagePresetService.imageUrlFor("마법사"))
        assertThat(reloaded.profileThumbnailBase64).isEqualTo(profileImagePresetService.thumbnailBase64For("마법사"))
    }

    @Test
    fun `닉네임과 프리셋을 함께 바꾸면 둘 다 반영된다`() {
        val user = saveUser()

        patch(user, """{"nickname":"이야기수집가","profileImagePreset":"수집가"}""").expectStatus().isOk

        val reloaded = reload(user)
        assertThat(reloaded.nickname).isEqualTo("이야기수집가")
        assertThat(reloaded.profileImageUrl).isEqualTo(profileImagePresetService.imageUrlFor("수집가"))
    }

    @Test
    fun `빈 본문은 400이다`() {
        patch(saveUser(), "{}").expectStatus().isBadRequest
    }

    @Test
    fun `닉네임에 null만 보내면 400이다`() {
        // 닉네임은 지울 수 없는 값이라 null 하나만 보내는 요청은 바꿀 것이 없는 요청과 같다.
        patch(saveUser(), """{"nickname":null}""").expectStatus().isBadRequest
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "가",                    // 1자
            "가나다라마바사아자차카타파하가나다라마바사",  // 21자
            "닉네임!",                // 특수문자
            "닉  네임",               // 연속 공백
            "ㄱㄴㄷ",                 // 자모 단독
            "닉네임😀",               // 이모지
            "   ",                   // 공백뿐(trim 후 빈 문자열)
        ],
    )
    fun `허용 규칙을 어기는 닉네임은 400이다`(nickname: String) {
        val user = saveUser()

        patch(user, """{"nickname":"$nickname"}""").expectStatus().isBadRequest

        assertThat(reload(user).nickname).isEqualTo("몽환적인 이야기꾼")
    }

    @ParameterizedTest
    @ValueSource(strings = ["닉 네임", " 닉네임", "닉네임 "])
    fun `공백 닉네임은 다듬지 않고 400으로 거부한다`(nickname: String) {
        val user = saveUser()
        patch(user, """{"nickname":"$nickname"}""").expectStatus().isBadRequest
        assertThat(reload(user).nickname).isEqualTo(user.nickname)
    }

    @Test
    fun `다른 회원과 대소문자나 공백만 다른 닉네임은 409다`() {
        saveUser(nickname = "Story Teller")
        val user = saveUser(nickname = "몽환적인 이야기꾼")

        patch(user, """{"nickname":"storyteller"}""")
            .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NICKNAME_TAKEN")

        assertThat(reload(user).nickname).isEqualTo("몽환적인 이야기꾼")
    }

    @Test
    fun `자기 닉네임의 대소문자만 바꾸는 것은 허용한다`() {
        val user = saveUser(nickname = "StoryTeller")

        patch(user, """{"nickname":"STORYTELLER"}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nickname").isEqualTo("STORYTELLER")
    }

    @Test
    fun `없는 프리셋 키는 400이다`() {
        val user = saveUser()

        patch(user, """{"profileImagePreset":"없는프리셋"}""").expectStatus().isBadRequest

        assertThat(reload(user).profileThumbnailBase64).isEqualTo("seed-thumb")
    }

    @Test
    fun `정지된 계정은 403이다`() {
        val suspended = saveUser(status = UserStatus.SUSPENDED)

        patch(suspended, """{"nickname":"바꾸고 싶다"}""").expectStatus().isForbidden

        assertThat(reload(suspended).nickname).isEqualTo("몽환적인 이야기꾼")
    }

    @Test
    fun `프리셋 목록은 키와 이미지 URL을 함께 돌려준다`() {
        val user = saveUser()

        restTestClient.get()
            .uri(PRESETS_PATH)
            .header("Authorization", bearer(user))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(profileImagePresetService.presetKeys().size)
            .jsonPath("$[0].key").isNotEmpty
            .jsonPath("$[0].imageUrl").isNotEmpty
    }

    private val searchIndices: OpenSearchIndicesClient = mock(OpenSearchIndicesClient::class.java) { call ->
        if (call.method.returnType == BooleanResponse::class.java) BooleanResponse(true) else RETURNS_DEFAULTS.answer(call)
    }

    /** 목 색인기가 실제 색인기와 리더에 위임하게 해 OpenSearch 클라이언트(목)까지 가는 호출을 센다. 컨텍스트 조합은 그대로다. */
    private fun indexThroughRealIndexer() {
        // 공유 목의 호출 기록이 다른 테스트에서 넘어오지 않게 비운다. searchClient는 Spring 목이라 테스트 뒤 초기화되지만
        // 컨텍스트 기동 때 받은 호출이 첫 테스트에 남을 수 있고, searchIndices는 필드라 수명주기 설정에 따라 공유될 수 있다.
        clearInvocations(searchClient, searchIndices)
        doReturn(searchIndices).`when`(searchClient).indices()
        val real = StorySearchIndexer(searchClient, searchProperties, documentReader, StorySearchIndexAdmin(searchClient, searchProperties))
        doAnswer { real.index(it.getArgument(0)); null }.`when`(indexer).index(anyLong())
    }

    private fun openSearchCalls(target: Any, name: String) = mockingDetails(target).invocations.count { it.method.name == name }

    @Suppress("UNCHECKED_CAST")
    private fun indexedDocumentIds(): List<String?> = mockingDetails(searchClient).invocations.filter { it.method.name == "index" }.map {
        (it.arguments[0] as java.util.function.Function<IndexRequest.Builder<Any>, ObjectBuilder<IndexRequest<Any>>>)
            .apply(IndexRequest.Builder()).build().id()
    }

    private fun awaitOpenSearchIndexCalls(expected: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (openSearchCalls(searchClient, "index") < expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(200) // 기대보다 많이 부르는지도 보려고 잠시 더 기다린다.
    }

    @Test
    fun `닉네임 변경 재색인은 소유 스토리마다 OpenSearch 색인과 인덱스 확인을 한 번씩 부른다`() {
        indexThroughRealIndexer()
        val user = saveUser(nickname = "기존작가")
        val owned = listOf(
            storyRepository.save(Story(userId = user.id, title = "공개 하나")),
            storyRepository.save(Story(userId = user.id, title = "공개 둘")),
            storyRepository.save(Story(userId = user.id, title = "비공개", visibility = StoryVisibility.PRIVATE)),
            storyRepository.save(Story(userId = user.id, title = "초안", status = StoryStatus.DRAFT)),
        )
        storyRepository.save(Story(userId = user.id, title = "삭제", deletedAt = Instant.now()))
        storyRepository.save(Story(userId = saveUser(nickname = "다른작가").id, title = "다른 회원"))

        patch(user, """{"nickname":"새로운작가"}""").expectStatus().isOk
        awaitOpenSearchIndexCalls(owned.size)

        // KNK-2078 묶음 경로 PR에서 바꿀 기대값: bulk 1회, 인덱스 확인 1회, index 0회. 문서 ID 검증은 bulk 요청의
        // operation ID 목록으로 옮겨 owned의 publicId 집합과 정확히 같음(빠짐, 섞임 없음)을 그대로 단언한다.
        assertThat(indexedDocumentIds()).containsExactlyInAnyOrderElementsOf(owned.map { it.publicId.toString() })
        assertThat(openSearchCalls(searchIndices, "exists")).isEqualTo(owned.size)
        assertThat(openSearchCalls(searchClient, "bulk")).isZero()
    }

    @Test
    fun `소유 스토리가 없는 회원의 닉네임 변경은 OpenSearch를 부르지 않는다`() {
        indexThroughRealIndexer()
        val user = saveUser(nickname = "기존작가")

        patch(user, """{"nickname":"새로운작가"}""").expectStatus().isOk
        awaitOpenSearchIndexCalls(0)

        assertThat(openSearchCalls(searchClient, "index")).isZero()
        assertThat(openSearchCalls(searchClient, "bulk")).isZero()
        assertThat(openSearchCalls(searchIndices, "exists")).isZero()
    }

    @Test
    fun `닉네임 변경은 삭제되지 않은 소유 스토리 네 건을 커밋 후 색인한다`() {
        val user = saveUser(nickname = "기존작가")
        val first = storyRepository.save(Story(userId = user.id, title = "공개 하나"))
        val second = storyRepository.save(Story(userId = user.id, title = "공개 둘"))
        val privateStory = storyRepository.save(Story(userId = user.id, title = "비공개", visibility = StoryVisibility.PRIVATE))
        val draft = storyRepository.save(Story(userId = user.id, title = "초안", status = StoryStatus.DRAFT))
        storyRepository.save(Story(userId = user.id, title = "삭제", deletedAt = Instant.now()))
        storyRepository.save(Story(userId = saveUser(nickname = "다른작가").id, title = "다른 회원"))
        storyRepository.save(Story(title = "게스트"))

        patch(user, """{"nickname":"새로운작가"}""").expectStatus().isOk

        verify(indexer, timeout(3000)).index(first.id)
        verify(indexer, timeout(3000)).index(second.id)
        verify(indexer, timeout(3000)).index(privateStory.id)
        verify(indexer, timeout(3000)).index(draft.id)
        verify(indexer, after(200).times(4)).index(anyLong())
        verifyNoMoreInteractions(indexer)
        assertThat(reload(user).nickname).isEqualTo("새로운작가")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{\"nickname\":\"기존작가\"}",
        "{\"profileImagePreset\":\"마법사\"}",
        "{\"nickname\":\"기존작가\",\"profileImagePreset\":\"마법사\"}",
    ])
    fun `닉네임이 그대로면 색인을 요청하지 않는다`(body: String) {
        val user = saveUser(nickname = "기존작가")
        storyRepository.save(Story(userId = user.id, title = "공개"))

        patch(user, body).expectStatus().isOk

        verify(indexer, after(200).never()).index(anyLong())
    }

    @Test
    fun `대소문자만 바꿔도 표시 닉네임이 달라지므로 재색인한다`() {
        val user = saveUser(nickname = "StoryTeller")
        val story = storyRepository.save(Story(userId = user.id, title = "공개"))

        patch(user, """{"nickname":"STORYTELLER"}""").expectStatus().isOk

        verify(indexer, timeout(3000)).index(story.id)
    }

    @Test
    fun `프로필 수정이 실패하면 닉네임과 색인을 반영하지 않는다`() {
        val user = saveUser(nickname = "기존작가")
        storyRepository.save(Story(userId = user.id, title = "공개"))

        patch(user, """{"nickname":"새로운작가","profileImagePreset":"없는프리셋"}""")
            .expectStatus().isBadRequest

        assertThat(reload(user).nickname).isEqualTo("기존작가")
        verify(indexer, after(200).never()).index(anyLong())
    }

    @Test
    fun `재색인 요청 뒤 프로필 트랜잭션이 롤백되면 닉네임과 색인을 반영하지 않는다`() {
        val user = saveUser(nickname = "기존작가")
        val story = storyRepository.save(Story(userId = user.id, title = "공개"))

        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            // 대상 조회가 비어 있으면 아래 index never가 공허하게 통과하므로, 롤백될 트랜잭션에서 대상이 있음을 먼저 확인한다.
            assertThat(storyRepository.findActiveIdsByUserId(user.id)).containsExactly(story.id)
            clearInvocations(storyRepository)
            profileUpdater.update(user.id, UpdateProfileRequest(nickname = "새로운작가"))
            status.setRollbackOnly()
        }

        // 재색인 대상 조회까지 진행된 뒤의 롤백이어야 이 테스트가 의미가 있다.
        verify(storyRepository).findActiveIdsByUserId(user.id)
        verify(indexer, after(300).never()).index(anyLong())
        assertThat(reload(user).nickname).isEqualTo("기존작가")
    }

    @Test
    fun `재색인 대상 조회가 실패하면 프로필 수정 전체가 롤백된다`() {
        val user = saveUser(nickname = "기존작가")
        storyRepository.save(Story(userId = user.id, title = "공개"))
        doThrow(IllegalStateException("db unavailable")).`when`(storyRepository).findActiveIdsByUserId(user.id)

        patch(user, """{"nickname":"새로운작가"}""").expectStatus().is5xxServerError

        verify(storyRepository).findActiveIdsByUserId(user.id)
        assertThat(reload(user).nickname).isEqualTo("기존작가")
        verify(indexer, after(200).never()).index(anyLong())
    }

    @Test
    fun `닉네임 변경 이벤트는 프로필 트랜잭션 밖에서 처리하지 않는다`() {
        val user = saveUser(nickname = "기존작가")
        storyRepository.save(Story(userId = user.id, title = "공개"))

        // 트랜잭션 없이 발행하면 AFTER_COMMIT 색인이 조용히 버려지므로 리스너가 MANDATORY로 거부한다.
        assertThrows<IllegalTransactionStateException> { events.publishEvent(UserNicknameChangedEvent(user.id)) }

        verify(storyRepository, never()).findActiveIdsByUserId(anyLong())
        verify(indexer, after(200).never()).index(anyLong())
    }

    @Test
    fun `탈퇴 행이 가진 닉네임은 프로필과 가입 발급에서 재사용할 수 있다`() {
        userRepository.save(User(nickname = "재사용작가", status = UserStatus.DELETED, deletedAt = Instant.now()))
        val user = saveUser(nickname = "현재작가")
        assertThat(userRepository.existsByNicknameKey("재사용작가")).isFalse()
        val generator = mock(com.knk.manyak.auth.social.NicknameGenerator::class.java)
        `when`(generator.generate()).thenReturn(com.knk.manyak.auth.social.GeneratedNickname("재사용작가", "작가"))
        val issuer = com.knk.manyak.auth.social.UniqueNicknameIssuer(generator, userRepository)
        assertThat(issuer.issue().text).isEqualTo("재사용작가")
        verify(generator, times(1)).generate()
        patch(user, """{"nickname":"재사용작가"}""").expectStatus().isOk
    }

    @ParameterizedTest
    @ValueSource(strings = ["탈퇴한사용자", "탈퇴한 사용자"])
    fun `탈퇴 익명화 닉네임은 사용할 수 없다`(nickname: String) {
        patch(saveUser(), """{"nickname":"$nickname"}""").expectStatus().isBadRequest
    }

    private companion object {
        const val PATH = "/api/v1/users/me"
        const val PRESETS_PATH = "/api/v1/profile-presets"
    }
}
