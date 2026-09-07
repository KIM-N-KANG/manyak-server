package com.knk.manyak.search.controller

import com.knk.manyak.search.dto.StorySearchAuthor
import com.knk.manyak.search.dto.StorySearchCursor
import com.knk.manyak.search.dto.StorySearchDocument
import com.knk.manyak.search.event.StoryIndexRequestedEvent
import com.knk.manyak.search.service.StorySearchDocumentReader
import com.knk.manyak.search.service.StorySearchIndexer
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.*
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.GeneralStoryCreationService
import com.knk.manyak.story.service.StoryEditService
import com.knk.manyak.story.service.StoryService
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.user.dto.UpdateProfileRequest
import com.knk.manyak.user.service.UserProfileService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.FieldValue
import org.opensearch.client.opensearch.core.SearchRequest
import org.opensearch.client.opensearch.core.SearchResponse
import org.opensearch.client.opensearch.core.search.Hit
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["manyak.ai.story.stub=true"])
class StorySearchControllerIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var sessions: com.knk.manyak.story.repository.StoryCreationSessionRepository
    @Autowired private lateinit var storylines: com.knk.manyak.story.repository.StoryCreationStorylineRepository
    @Autowired private lateinit var jwt: com.knk.manyak.auth.jwt.JwtTokenProvider
    @Autowired private lateinit var storyImages: com.knk.manyak.story.service.StoryImageService
    @Autowired private lateinit var characters: StoryCharacterRepository
    @Autowired private lateinit var general: GeneralStoryCreationService
    @Autowired private lateinit var edit: StoryEditService
    @Autowired private lateinit var service: StoryService
    @Autowired private lateinit var profiles: UserProfileService
    @Autowired private lateinit var reader: StorySearchDocumentReader
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var events: ApplicationEventPublisher
    @MockitoBean private lateinit var openSearch: OpenSearchClient
    @MockitoBean private lateinit var indexer: StorySearchIndexer

    @BeforeEach
    fun setUp() {
        cleaner.cleanAll()
        `when`(openSearch.search(any(SearchRequest::class.java), eq(StorySearchDocument::class.java))).thenReturn(response(emptyList()))
    }

    @Test
    fun `검색어 누락과 길이 오류는 상세 조회 404가 아닌 400이다`() {
        client.get().uri("/api/v1/stories/search").exchange().expectStatus().isBadRequest
        for (q in listOf("", "가", "가".repeat(101))) {
            client.get().uri { it.path("/api/v1/stories/search").queryParam("q", q).build() }.exchange().expectStatus().isBadRequest
        }
        verifyNoInteractions(openSearch)
    }

    @Test
    fun `공개 검색은 인증 없이 trim 질의 필터 관련도 정렬과 limit 보정을 적용한다`() {
        for ((limit, size) in listOf(-1 to 2, 100 to 51)) {
            client.get().uri { it.path("/api/v1/stories/search").queryParam("q", " 왕국 ").queryParam("limit", limit).build() }
                .header("Authorization", "Bearer expired-token")
                .exchange().expectStatus().isOk.expectBody().jsonPath("$.items").isEmpty.jsonPath("$.nextCursor").isEmpty
            val request = capturedRequest()
            assertEquals(size, request.size())
            val bool = request.query()!!.bool()
            assertEquals("왕국", bool.must().single().multiMatch().query())
            assertEquals(listOf("title^3", "oneLineIntro", "genres", "characterNames"), bool.must().single().multiMatch().fields())
            assertEquals("best_fields", bool.must().single().multiMatch().type()!!.jsonValue())
            assertEquals("visible", bool.filter().single().term().field())
            assertTrue(bool.filter().single().term().value().booleanValue())
            assertEquals("desc", request.sort()[0].score().order()!!.jsonValue())
            assertEquals("createdAt", request.sort()[1].field().field())
            assertEquals("desc", request.sort()[1].field().order()!!.jsonValue())
            assertEquals("publicId", request.sort()[2].field().field())
            assertEquals("asc", request.sort()[2].field().order()!!.jsonValue())
            clearInvocations(openSearch)
        }
    }

    @Test
    fun `카드 매핑과 커서 왕복은 마지막 반환 문서의 정렬값을 사용한다`() {
        val document = StorySearchDocument(
            publicId = UUID.randomUUID().toString(), title = "왕국의 마법사", oneLineIntro = "소개", genres = listOf("판타지"),
            thumbnailUrlSm = "https://example.com/small.png", author = StorySearchAuthor(nickname = "작가"),
            turnCount = 12, likeCount = 3, createdAt = 1780000000000, visible = true,
        )
        val owner = users.save(User(nickname = "카드작가", status = UserStatus.ACTIVE))
        stories.save(Story(publicId = UUID.fromString(document.publicId), userId = owner.id, title = document.title))
        val values = listOf(FieldValue.of(3.5), FieldValue.of(document.createdAt), FieldValue.of(document.publicId))
        val hit = Hit.Builder<StorySearchDocument>().index("stories-dev").id(document.publicId).source(document).sort(values).build()
        `when`(openSearch.search(any(SearchRequest::class.java), eq(StorySearchDocument::class.java))).thenReturn(response(listOf(hit, hit)))
        val body = client.get().uri("/api/v1/stories/search?q=왕국&limit=1").exchange().expectStatus().isOk
            .expectBody(StoryPageResponse::class.java).returnResult().responseBody!!
        assertEquals(document.toSummary(), body.items.single())
        assertNotNull(body.nextCursor)
        assertNull(body.items.single().author!!.id)
        assertEquals(Instant.ofEpochMilli(document.createdAt), body.items.single().createdAt)
        clearInvocations(openSearch)
        client.get().uri { it.path("/api/v1/stories/search").queryParam("q", "왕국").queryParam("cursor", body.nextCursor!!).build() }
            .exchange().expectStatus().isOk
        assertEquals(values, capturedRequest().searchAfter())
        client.get().uri { it.path("/api/v1/stories/search").queryParam("q", "마법").queryParam("cursor", body.nextCursor!!).build() }
            .exchange().expectStatus().isBadRequest
        client.get().uri("/api/v1/stories/search?q=왕국&cursor=broken").exchange().expectStatus().isBadRequest
    }

    @Test
    fun `색인에 남은 비공개 스토리는 DB 게이트로 제외하고 즉시 재색인한다`() {
        val user = users.save(User(nickname = "철회작가", status = UserStatus.ACTIVE))
        val visible = stories.save(Story(userId = user.id, title = "공개 왕국"))
        val withdrawn = stories.save(Story(userId = user.id, title = "비공개 왕국", visibility = StoryVisibility.PRIVATE))
        `when`(openSearch.search(any(SearchRequest::class.java), eq(StorySearchDocument::class.java)))
            .thenReturn(response(listOf(staleHit(visible), staleHit(withdrawn))))
        val body = client.get().uri("/api/v1/stories/search?q=왕국").exchange().expectStatus().isOk
            .expectBody(StoryPageResponse::class.java).returnResult().responseBody!!
        assertEquals(listOf(visible.publicId.toString()), body.items.map { it.id })
        verify(indexer).index(withdrawn.id)
        verify(indexer, never()).index(visible.id)
    }

    @Test
    fun `삭제 초안 게스트 부재 문서는 숨기고 빈 페이지 커서는 필터 전 hit를 따른다`() {
        val user = users.save(User(nickname = "게이트작가", status = UserStatus.ACTIVE))
        val hidden = listOf(
            Story(userId = user.id, title = "삭제 왕국", deletedAt = Instant.now()),
            Story(userId = user.id, title = "초안 왕국", status = StoryStatus.DRAFT),
            Story(title = "게스트 왕국"),
        ).map(stories::save)
        val missing = Story(title = "없는 왕국")
        val last = staleHit(missing)
        val extra = staleHit(hidden.first())
        `when`(openSearch.search(any(SearchRequest::class.java), eq(StorySearchDocument::class.java)))
            .thenReturn(response(hidden.map(::staleHit) + last + extra))
        doThrow(IllegalStateException("unavailable")).`when`(indexer).index(hidden.first().id)
        val body = client.get().uri("/api/v1/stories/search?q=왕국&limit=4").exchange().expectStatus().isOk
            .expectBody(StoryPageResponse::class.java).returnResult().responseBody!!
        assertTrue(body.items.isEmpty())
        assertEquals(StorySearchCursor.fromSort(last.sort()).encode("왕국"), body.nextCursor)
        hidden.forEach { verify(indexer).index(it.id) }
        verifyNoMoreInteractions(indexer)
    }

    private fun staleHit(story: Story): Hit<StorySearchDocument> = Hit.Builder<StorySearchDocument>()
        .index("stories-dev").id(story.publicId.toString())
        .source(StorySearchDocument(publicId = story.publicId.toString(), title = story.title, visible = true))
        .sort(listOf(FieldValue.of(2.0), FieldValue.of(story.createdAt.toEpochMilli()), FieldValue.of(story.publicId.toString())))
        .build()

    @Test
    fun `인덱스가 아직 없으면 인증 없이 빈 검색 페이지를 반환한다`() {
        val error = org.opensearch.client.opensearch._types.OpenSearchException(
            org.opensearch.client.opensearch._types.ErrorResponse.Builder().status(404)
                .error { it.type("index_not_found_exception").reason("test") }.build(),
        )
        `when`(openSearch.search(any(SearchRequest::class.java), eq(StorySearchDocument::class.java))).thenThrow(error)
        client.get().uri("/api/v1/stories/search?q=왕국").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.items").isEmpty.jsonPath("$.nextCursor").isEmpty
    }

    @Test
    fun `색인 실패는 검색 503으로 응답한다`() {
        `when`(openSearch.search(any(SearchRequest::class.java), eq(StorySearchDocument::class.java))).thenThrow(java.io.IOException("unavailable"))
        client.get().uri("/api/v1/stories/search?q=왕국").exchange().expectStatus().isEqualTo(503)
    }

    @Test
    fun `일반 제작 수정 공개 전환 좋아요 취소 삭제 닉네임은 커밋 후 색인한다`() {
        val user = users.save(User(nickname = "검색작가", status = UserStatus.ACTIVE))
        val created = general.createGeneralStory(
            CreateGeneralStoryRequest("왕국", "한 줄 소개", genres = listOf("판타지"),
                storySettings = GeneralStorySettingsInput("세계", "인물", "역할", "규칙"),
                startSettings = listOf(GeneralStartSettingInput(name = "시작", prologue = "프롤로그", startSituation = "상황", suggestedInputs = listOf("첫째", "둘째", "셋째")))),
            user.id,
        )
        val story = stories.findByPublicIdAndDeletedAtIsNull(UUID.fromString(created.id))!!
        indexed(story.id)
        assertFalse(reader.read(story.id)!!.visible)
        edit.updateStory(created.id, user.id, UpdateStoryRequest(title = "마법 왕국"))
        indexed(story.id)
        assertEquals("마법 왕국", reader.read(story.id)!!.title)
        edit.updateStory(created.id, user.id, UpdateStoryRequest(visibility = StoryVisibility.PUBLIC))
        indexed(story.id)
        assertTrue(reader.read(story.id)!!.visible)
        service.like(created.id, user.id)
        indexed(story.id)
        assertEquals(1L, reader.read(story.id)!!.likeCount)
        service.unlike(created.id, user.id)
        indexed(story.id)
        assertEquals(0L, reader.read(story.id)!!.likeCount)
        val other = stories.save(Story(userId = user.id, title = "다른 스토리"))
        profiles.updateProfile(user.id, UpdateProfileRequest(nickname = "새검색작가"))
        verify(indexer, timeout(3000)).index(story.id)
        verify(indexer, timeout(3000)).index(other.id)
        clearInvocations(indexer)
        assertEquals("새검색작가", reader.read(story.id)!!.author!!.nickname)
        edit.updateStory(created.id, user.id, UpdateStoryRequest(visibility = StoryVisibility.PRIVATE))
        indexed(story.id)
        assertFalse(reader.read(story.id)!!.visible)
        service.deleteStory(created.id, user.id)
        indexed(story.id)
        assertFalse(reader.read(story.id)!!.visible)
    }

    @Test
    fun `색인은 롤백과 트랜잭션 밖 이벤트를 무시하고 실패가 원 커밋을 깨지 않는다`() {
        TransactionTemplate(transactions).executeWithoutResult {
            events.publishEvent(StoryIndexRequestedEvent(9001))
            it.setRollbackOnly()
        }
        events.publishEvent(StoryIndexRequestedEvent(9002))
        verify(indexer, after(200).never()).index(9001)
        verify(indexer, never()).index(9002)
        doThrow(IllegalStateException("unavailable")).`when`(indexer).index(9003)
        assertDoesNotThrow {
            TransactionTemplate(transactions).executeWithoutResult { events.publishEvent(StoryIndexRequestedEvent(9003)) }
        }
        verify(indexer, timeout(3000)).index(9003)
    }

    @Test
    fun `색인 문서는 초안 비공개 삭제 게스트를 숨기고 인물명을 포함한다`() {
        val user = users.save(User(nickname = "문서작가", status = UserStatus.ACTIVE))
        val samples = listOf(
            Story(userId = user.id, title = "공개", genre = "판타지, , 로맨스"),
            Story(userId = user.id, title = "초안", status = StoryStatus.DRAFT),
            Story(userId = user.id, title = "비공개", visibility = StoryVisibility.PRIVATE),
            Story(userId = user.id, title = "삭제", deletedAt = Instant.now()),
            Story(title = "게스트"),
        ).map(stories::save)
        characters.save(StoryCharacter(story = samples.first(), name = "마법사"))
        assertEquals(listOf(true, false, false, false, false), samples.map { reader.read(it.id)!!.visible })
        val document = reader.read(samples.first().id)!!
        assertEquals(listOf("판타지", "로맨스"), document.genres)
        assertEquals(listOf("마법사"), document.characterNames)
        assertEquals("", document.oneLineIntro)
        assertNull(document.author!!.id)
    }

    @Test
    fun `간편 제작의 완성 저장은 인물과 함께 색인하고 replay는 다시 색인하지 않는다`() {
        val user = users.save(User(nickname = "간편검색작가", status = UserStatus.ACTIVE))
        val session = sessions.save(StoryCreationSession(userId = user.id, status = StoryCreationSessionStatus.STORYLINES_GENERATED))
        val storyline = storylines.save(StoryCreationStoryline(creationSession = session, storylineText = "마법 왕국", storylineOrder = 1))
        val body = """{"requestId":"${UUID.randomUUID()}","simpleCreationId":${session.id},"storylineId":${storyline.id},"additionalInfos":[]}"""
        fun complete() = client.post().uri("/api/v1/stories/simple")
            .header("Authorization", "Bearer ${jwt.issueAccessToken(user.publicId)}")
            .header("X-Manyak-Device-Id", "search-completion-test")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isCreated
        val result = complete().expectBody(SimpleStoryCreateResponse::class.java).returnResult().responseBody!!
        val story = stories.findByPublicIdAndDeletedAtIsNull(UUID.fromString(result.id))!!
        indexed(story.id)
        assertFalse(reader.read(story.id)!!.visible)
        complete()
        verify(indexer, after(200).never()).index(story.id)
    }

    @Test
    fun `표지 삭제는 검색 카드도 프리셋 폴백으로 갱신한다`() {
        val user = users.save(User(nickname = "표지검색작가", status = UserStatus.ACTIVE))
        val story = stories.save(Story(userId = user.id, title = "표지", thumbnailImageUrl = "https://example.com/cover.webp"))
        storyImages.deleteThumbnail(story.publicId.toString(), user.id)
        indexed(story.id)
        assertNull(reader.read(story.id)!!.thumbnailUrlSm)
    }

    @Test
    fun `검색 OpenAPI는 필수 q와 200 400 503 응답을 문서화한다`() {
        client.get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.paths['/api/v1/stories/search'].get.responses['200']").exists()
            .jsonPath("$.paths['/api/v1/stories/search'].get.responses['400']").exists()
            .jsonPath("$.paths['/api/v1/stories/search'].get.responses['503']").exists()
            .jsonPath("$.paths['/api/v1/stories/search'].get.parameters[?(@.name == 'q')].required").isEqualTo(listOf(true))
    }

    private fun indexed(id: Long) {
        verify(indexer, timeout(3000)).index(id)
        clearInvocations(indexer)
    }
    private fun capturedRequest(): SearchRequest {
        val captor = ArgumentCaptor.forClass(SearchRequest::class.java)
        verify(openSearch).search(captor.capture(), eq(StorySearchDocument::class.java))
        return captor.value
    }
    private fun response(hits: List<Hit<StorySearchDocument>>): SearchResponse<StorySearchDocument> =
        SearchResponse.Builder<StorySearchDocument>().took(1).timedOut(false)
            .shards { it.total(1).successful(1).failed(0) }.hits { it.hits(hits) }.build()
}
