package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.story.dto.StoryPageResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryLike
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryLikeRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.StoryExploreSeedSource
import com.knk.manyak.support.ConsentGateIntegrationTest
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import com.knk.manyak.support.MockedBoundaryIntegrationTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.client.RestTestClient
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

class StoryExploreControllerIntegrationTests : MockedBoundaryIntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var likes: StoryLikeRepository
    @Autowired private lateinit var seeds: StoryExploreSeedSource

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        `when`(seeds.nextSeed()).thenReturn(42L)
    }

    private fun story(index: Int, owner: Long? = null): Story = stories.save(Story(
        publicId = UUID(0, index.toLong()), userId = owner, title = "스토리 $index",
        oneLineIntro = "소개", genre = "판타지", thumbnailImageUrl = "https://example.com/$index.webp",
    ))

    private fun get(query: String = "", token: String? = null): RestTestClient.ResponseSpec = client.get()
        .uri("/api/v1/stories/explore$query")
        .headers { if (token != null) it.setBearerAuth(token) }.exchange()

    private fun page(query: String = "", token: String? = null): StoryPageResponse = get(query, token)
        .expectStatus().isOk.expectBody(StoryPageResponse::class.java).returnResult().responseBody!!

    private fun hash(seed: Long, id: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("MD5").digest("$seed$id".toByteArray(Charsets.UTF_8)),
    )

    private fun encoded(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

    @Test fun `표지가 있는 발행 공개만 노출하고 회원 요청만 자기 소유를 제외한다`() {
        val me = users.save(User(nickname = "요청자"))
        val author = users.save(User(publicId = UUID.fromString(IntegrationTest.OFFICIAL_USER_PUBLIC_ID), nickname = "공식 작가"))
        val own = story(1, me.id)
        val member = story(2, author.id)
        val guest = story(3)
        val preset = story(4).apply {
            thumbnailModerationStatus = ImageModerationStatus.REJECTED
            thumbnailImageKey = "thumb_0001"
        }.let(stories::save)
        stories.save(story(5).apply { thumbnailImageUrl = null })
        stories.save(story(6).apply { thumbnailImageUrl = "  " })
        stories.save(story(7).apply { thumbnailModerationStatus = ImageModerationStatus.PENDING })
        stories.save(story(8).apply { thumbnailModerationStatus = ImageModerationStatus.REJECTED })
        stories.save(story(9).apply { visibility = StoryVisibility.PRIVATE })
        stories.save(story(10).apply { status = StoryStatus.DRAFT })
        stories.save(story(11).apply { deletedAt = Instant.now() })
        chats.save(StoryChat(storyId = member.id, currentTurn = 2, regeneratedCount = 1))
        likes.save(StoryLike(storyId = member.id, userId = me.id))

        val anonymous = page().items
        assertEquals(setOf(own, member, guest, preset).map { it.publicId.toString() }.toSet(), anonymous.map { it.id }.toSet())
        assertTrue(anonymous.all { it.thumbnailUrlSm != null })
        assertNull(anonymous.single { it.id == guest.publicId.toString() }.author)
        assertTrue(anonymous.single { it.id == preset.publicId.toString() }.thumbnailUrlSm!!.endsWith("thumb_0001_sm.png"))
        val card = anonymous.single { it.id == member.publicId.toString() }
        assertTrue(card.isOriginal)
        assertEquals("공식 작가", card.author!!.nickname)
        assertNull(card.author!!.id)
        assertEquals(3L, card.turnCount)
        assertEquals(1L, card.likeCount)
        assertEquals(listOf("판타지"), card.genres)
        assertEquals(setOf(member, guest, preset).map { it.publicId.toString() }.toSet(),
            page(token = jwt.issueAccessToken(me.publicId)).items.map { it.id }.toSet())
        assertEquals(anonymous.map { it.id }, page(token = "invalid-access-token").items.map { it.id })
    }

    @Test fun `새 요청은 주입된 새 시드로 순서가 바뀌고 MD5 오름차순이다`() {
        val ids = (1..12).map { story(it).publicId.toString() }
        `when`(seeds.nextSeed()).thenReturn(42L, 43L)
        val first = page().items.map { it.id }
        val second = page().items.map { it.id }
        assertEquals(ids.sortedBy { hash(42, it) }, first)
        assertEquals(ids.sortedBy { hash(43, it) }, second)
        assertNotEquals(first, second)
        verify(seeds, times(2)).nextSeed()
    }

    @Test fun `같은 커서로 끝까지 중복 누락 없이 읽고 마지막 커서는 null이다`() {
        val ids = (1..17).map { story(it).publicId.toString() }
        `when`(seeds.nextSeed()).thenReturn(Long.MIN_VALUE)
        val all = mutableListOf<String>()
        var cursor: String? = null
        do {
            val response = page("?limit=3" + (cursor?.let { "&cursor=$it" } ?: ""))
            all += response.items.map { it.id }
            cursor = response.nextCursor
            if (cursor != null) {
                assertFalse(cursor.contains('='))
                val parts = String(Base64.getUrlDecoder().decode(cursor)).split(':')
                assertEquals(listOf("x", Long.MIN_VALUE.toString(), hash(Long.MIN_VALUE, all.last()), all.last()), parts)
            }
            assertTrue(all.size <= ids.size)
        } while (cursor != null)
        assertEquals(ids.sortedBy { hash(Long.MIN_VALUE, it) }, all)
        assertEquals(all.size, all.toSet().size)
        verify(seeds, times(1)).nextSeed()
    }

    @Test fun `limit 기본값과 상하한을 적용하고 잘못된 limit과 커서는 400이다`() {
        (1..55).forEach { story(it) }
        assertEquals(20, page().items.size)
        assertEquals(1, page("?limit=0").items.size)
        assertEquals(50, page("?limit=999").items.size)
        get("?limit=not-a-number").expectStatus().isBadRequest
        val id = UUID(0, 1).toString()
        val value = hash(42, id)
        val invalid = listOf("broken!", encoded("l:42:$value:$id"), encoded("x:42:$id"),
            encoded("x:invalid:$value:$id"), encoded("x:9223372036854775808:$value:$id"),
            encoded("x:42:bad:$id"), encoded("x:42:$value:not-a-uuid"))
        invalid.forEach { get("?cursor=$it").expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST") }
    }
}

class StoryExploreConsentIntegrationTests : ConsentGateIntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner

    @Test fun `미동의 회원과 익명 요청의 동의 게이트는 공개 목록과 같다`() {
        cleaner.cleanAll()
        val member = users.save(User(nickname = "미동의 회원"))
        for (path in listOf("/api/v1/stories", "/api/v1/stories/explore")) {
            client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAccessToken(member.publicId)}")
                .exchange().expectStatus().isForbidden.expectBody().jsonPath("$.code").isEqualTo("CONSENT_REQUIRED")
            client.get().uri(path).exchange().expectStatus().isOk
        }
    }
}
