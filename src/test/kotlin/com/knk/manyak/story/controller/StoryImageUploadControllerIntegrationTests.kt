package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Duration

/**
 * 스토리 이미지 업로드(KNK-1126, 스펙 §4-3-8).
 *
 * - presign은 회원 소유 스토리만이고 형식·크기를 검증한다. 게스트 소유는 400, 타인은 403, 미인증은 401이다.
 * - 연결 시 객체 키가 이 스토리의 업로드 prefix 아래인지 보고 `HEAD`로 존재·크기·형식을 재검증한다.
 * - 검수 게이트: 노출·AI 전달은 `APPROVED`만이고, 소유자의 편집 폼만 상태와 함께 전부 본다.
 * - 삭제는 DB 참조만 지운다(S3 객체는 남긴다 — 지난 채팅 마커가 깨지지 않게).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryImageUploadControllerIntegrationTests {

    @MockitoBean private lateinit var uploadedImageStorage: UploadedImageStorage

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyCharacterRepository: StoryCharacterRepository
    @Autowired private lateinit var storyCharacterImageRepository: StoryCharacterImageRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        `when`(uploadedImageStorage.isEnabled()).thenReturn(true)
        `when`(uploadedImageStorage.presignPut(anyString(), anyString(), anyLong(), anyDuration()))
            .thenReturn("https://s3.example.test/signed")
        `when`(uploadedImageStorage.serveUrlOf(anyString())).thenAnswer { "$BASE_URL/${it.arguments[0]}" }
        `when`(uploadedImageStorage.head(anyString())).thenReturn(UploadedObject("image/webp", 1024))
    }

    private fun saveUser(nickname: String = "작가", status: UserStatus = UserStatus.ACTIVE): User =
        userRepository.save(User(nickname = nickname, status = status))

    private fun bearer(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    private fun saveStory(owner: User?): Story =
        storyRepository.save(Story(userId = owner?.id, title = "표지 바꿀 스토리", thumbnailImageKey = "thumb_0001"))

    private fun saveCharacter(story: Story, name: String = "세린"): StoryCharacter =
        storyCharacterRepository.save(StoryCharacter(story = story, name = name))

    private fun presign(story: Story, user: User?, body: String) =
        restTestClient.post()
            .uri("/api/v1/stories/${story.publicId}/images/presign")
            .apply { user?.let { header("Authorization", bearer(it)) } }
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()

    private fun coverBody() = """{"kind":"COVER","contentType":"image/webp","contentLength":1024}"""

    private fun coverKey(story: Story) = "thumbnails/uploaded/${story.publicId}/cover-1.webp"

    private fun characterKey(story: Story) = "characters/uploaded/${story.publicId}/face-1.webp"

    private fun patchStory(story: Story, user: User?, body: String) =
        restTestClient.patch()
            .uri("/api/v1/stories/${story.publicId}")
            .apply { user?.let { header("Authorization", bearer(it)) } }
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()

    private fun addImage(story: Story, character: StoryCharacter, user: User, body: String) =
        restTestClient.post()
            .uri("/api/v1/stories/${story.publicId}/characters/${character.publicId}/images")
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()

    private fun addImageBody(story: Story, imageName: String) =
        """{"objectKey":"${characterKey(story)}","imageName":"$imageName"}"""

    // ---- presign ----

    @Test
    fun `소유자는 presign을 받고 키가 이 스토리의 업로드 경로 아래다`() {
        val owner = saveUser()
        val story = saveStory(owner)

        presign(story, owner, coverBody())
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.uploadUrl").isNotEmpty
            .jsonPath("$.objectKey").value<String> {
                assertThat(it).startsWith("thumbnails/uploaded/${story.publicId}/")
                assertThat(it).endsWith(".webp")
            }
            .jsonPath("$.expiresInSeconds").isEqualTo(600)
    }

    @Test
    fun `게스트 소유 스토리는 presign이 400이다`() {
        // 소유자가 없으면 올린 이미지의 책임 주체가 없다. 이관 뒤에 올린다.
        presign(saveStory(owner = null), saveUser(), coverBody()).expectStatus().isBadRequest
    }

    @Test
    fun `타인의 스토리는 presign이 403이고 미인증은 401이다`() {
        val story = saveStory(saveUser("주인"))

        presign(story, saveUser("타인"), coverBody()).expectStatus().isForbidden
        presign(story, null, coverBody()).expectStatus().isUnauthorized
    }

    @Test
    fun `지원하지 않는 형식과 5MB 초과는 presign이 400이다`() {
        val owner = saveUser()
        val story = saveStory(owner)

        presign(story, owner, """{"kind":"COVER","contentType":"image/gif","contentLength":1024}""")
            .expectStatus().isBadRequest
        presign(story, owner, """{"kind":"COVER","contentType":"image/webp","contentLength":6291456}""")
            .expectStatus().isBadRequest
    }

    @Test
    fun `없는 스토리는 presign이 404다`() {
        restTestClient.post()
            .uri("/api/v1/stories/${java.util.UUID.randomUUID()}/images/presign")
            .header("Authorization", bearer(saveUser()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(coverBody())
            .exchange()
            .expectStatus().isNotFound
    }

    // ---- 표지 ----

    @Test
    fun `표지를 연결하면 편집 폼과 DB에 서빙 URL이 실린다`() {
        val owner = saveUser()
        val story = saveStory(owner)

        patchStory(story, owner, """{"thumbnailObjectKey":"${coverKey(story)}"}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.thumbnailUrl").isEqualTo("$BASE_URL/${coverKey(story)}")
            .jsonPath("$.thumbnailModerationStatus").isEqualTo("APPROVED")

        assertThat(storyRepository.findById(story.id).get().thumbnailImageUrl)
            .isEqualTo("$BASE_URL/${coverKey(story)}")
    }

    @Test
    fun `다른 스토리의 객체 키로 표지를 연결하면 400이다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        val other = saveStory(owner)

        patchStory(story, owner, """{"thumbnailObjectKey":"${coverKey(other)}"}""").expectStatus().isBadRequest

        assertThat(storyRepository.findById(story.id).get().thumbnailImageUrl).isNull()
    }

    @Test
    fun `업로드가 끝나지 않은 키는 400 UPLOAD_NOT_FOUND다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        `when`(uploadedImageStorage.head(anyString())).thenReturn(null)

        patchStory(story, owner, """{"thumbnailObjectKey":"${coverKey(story)}"}""")
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("UPLOAD_NOT_FOUND")
    }

    @Test
    fun `올라온 객체가 5MB를 넘으면 400이다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        `when`(uploadedImageStorage.head(anyString())).thenReturn(UploadedObject("image/webp", 6L * 1024 * 1024))

        patchStory(story, owner, """{"thumbnailObjectKey":"${coverKey(story)}"}""").expectStatus().isBadRequest
    }

    @Test
    fun `게스트 스토리는 표지를 연결할 수 없다`() {
        val story = saveStory(owner = null)

        // 게스트 스토리는 익명으로 수정할 수 있지만 이미지는 못 올린다.
        patchStory(story, null, """{"thumbnailObjectKey":"${coverKey(story)}"}""").expectStatus().isBadRequest
    }

    @Test
    fun `표지를 지우면 프리셋으로 내려가고 다시 지워도 204다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        patchStory(story, owner, """{"thumbnailObjectKey":"${coverKey(story)}"}""").expectStatus().isOk

        repeat(2) {
            restTestClient.delete()
                .uri("/api/v1/stories/${story.publicId}/thumbnail")
                .header("Authorization", bearer(owner))
                .exchange()
                .expectStatus().isNoContent
        }

        val reloaded = storyRepository.findById(story.id).get()
        assertThat(reloaded.thumbnailImageUrl).isNull()
        // 프리셋 키는 그대로라 노출이 프리셋으로 떨어진다(사라지지 않는다).
        assertThat(reloaded.thumbnailImageKey).isEqualTo("thumb_0001")
        assertThat(reloaded.thumbnailModerationStatus).isEqualTo(ImageModerationStatus.APPROVED)
    }

    // ---- 인물 이미지 ----

    @Test
    fun `인물 이미지를 연결하면 201이고 편집 폼에 실린다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        val character = saveCharacter(story)

        addImage(story, character, owner, addImageBody(story, "세린_웃음"))
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.imageName").isEqualTo("세린_웃음")
            .jsonPath("$.imageUrl").isEqualTo("$BASE_URL/${characterKey(story)}")
            .jsonPath("$.moderationStatus").isEqualTo("APPROVED")

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}/edit")
            .header("Authorization", bearer(owner))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.characters[0].name").isEqualTo("세린")
            .jsonPath("$.characters[0].images[0].imageName").isEqualTo("세린_웃음")
            .jsonPath("$.characters[0].images[0].moderationStatus").isEqualTo("APPROVED")
    }

    @Test
    fun `이름 형식을 어기면 400이다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        val character = saveCharacter(story)

        // 다른 인물 이름 / 접미 없음 / 접미 21자
        listOf("카일_웃음", "세린", "세린_${"가".repeat(21)}").forEach { name ->
            addImage(story, character, owner, addImageBody(story, name)).expectStatus().isBadRequest
        }
        assertThat(storyCharacterImageRepository.count()).isZero()
    }

    @Test
    fun `같은 이름의 이미지는 409다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        val character = saveCharacter(story)
        addImage(story, character, owner, addImageBody(story, "세린_기본")).expectStatus().isCreated

        addImage(story, character, owner, addImageBody(story, "세린_기본"))
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)

        assertThat(storyCharacterImageRepository.count()).isEqualTo(1)
    }

    @Test
    fun `인물당 10장을 넘기면 400이다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        val character = saveCharacter(story)
        (1..StoryCharacterImage.MAX_IMAGES_PER_CHARACTER).forEach {
            addImage(story, character, owner, addImageBody(story, "세린_표정$it")).expectStatus().isCreated
        }

        addImage(story, character, owner, addImageBody(story, "세린_초과")).expectStatus().isBadRequest

        assertThat(storyCharacterImageRepository.count())
            .isEqualTo(StoryCharacterImage.MAX_IMAGES_PER_CHARACTER.toLong())
    }

    @Test
    fun `인물 이미지를 지우면 204이고 다시 지워도 204다`() {
        val owner = saveUser()
        val story = saveStory(owner)
        val character = saveCharacter(story)
        val imageId = addImage(story, character, owner, addImageBody(story, "세린_기본"))
            .expectStatus().isCreated
            .expectBody()
            .returnResult()
            .let { IMAGE_ID_PATTERN.find(String(it.responseBody!!))!!.groupValues[1] }

        repeat(2) {
            restTestClient.delete()
                .uri("/api/v1/stories/${story.publicId}/characters/${character.publicId}/images/$imageId")
                .header("Authorization", bearer(owner))
                .exchange()
                .expectStatus().isNoContent
        }
        assertThat(storyCharacterImageRepository.count()).isZero()
    }

    @Test
    fun `타인은 인물 이미지를 연결할 수 없다`() {
        val owner = saveUser("주인")
        val story = saveStory(owner)
        val character = saveCharacter(story)

        addImage(story, character, saveUser("타인"), addImageBody(story, "세린_기본")).expectStatus().isForbidden
    }

    // ---- 검수 게이트 ----

    @Test
    fun `검수를 통과하지 못한 인물 이미지는 상세에서 빠지고 편집 폼에는 상태와 함께 보인다`() {
        val owner = saveUser()
        val story = saveStory(owner).apply { visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC }
            .let(storyRepository::save)
        val character = saveCharacter(story)
        storyCharacterImageRepository.save(
            StoryCharacterImage(
                character = character,
                imageName = "세린_기본",
                imageUrl = "$BASE_URL/${characterKey(story)}",
                moderationStatus = ImageModerationStatus.PENDING,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // 인물은 남고 대표 이미지만 빠진다(구성은 그대로 보여준다).
            .jsonPath("$.characters[0].name").isEqualTo("세린")
            .jsonPath("$.characters[0].imageUrl").doesNotExist()

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}/edit")
            .header("Authorization", bearer(owner))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.characters[0].images[0].moderationStatus").isEqualTo("PENDING")
    }

    @Test
    fun `검수를 통과하지 못한 표지는 상세에서 프리셋으로 떨어진다`() {
        val owner = saveUser()
        val story = storyRepository.save(
            Story(
                userId = owner.id,
                title = "검수 대기 표지",
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                thumbnailImageKey = "thumb_0001",
                thumbnailImageUrl = "$BASE_URL/thumbnails/uploaded/pending.webp",
                thumbnailModerationStatus = ImageModerationStatus.PENDING,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.thumbnailUrl").value<String> {
                // 업로드 URL이 아니라 프리셋 키로 조합한 URL이어야 한다.
                assertThat(it).doesNotContain("uploaded")
                assertThat(it).contains("thumb_0001")
            }
    }

    /**
     * Kotlin의 non-null 파라미터에 Mockito `any()`가 null을 넘겨 NPE가 난다. 매처는 스택에 등록하고
     * 값은 더미를 돌려 호출부의 null 검사를 통과시킨다(Mockito는 반환값을 쓰지 않는다).
     */
    private fun anyDuration(): Duration {
        org.mockito.ArgumentMatchers.any(Duration::class.java)
        return Duration.ZERO
    }

    private companion object {
        const val BASE_URL = "https://cdn.test"
        val IMAGE_ID_PATTERN = """"id":"([^"]+)"""".toRegex()
    }
}
