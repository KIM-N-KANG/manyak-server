package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.support.DatabaseCleaner
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryDetailControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyEndingRepository: StoryEndingRepository

    @Autowired
    private lateinit var storyCharacterRepository: StoryCharacterRepository

    @Autowired
    private lateinit var storyCharacterImageRepository: com.knk.manyak.story.repository.StoryCharacterImageRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `작성자 있는 스토리는 상세 author에 닉네임·프로필을 싣고 내부 id는 노출하지 않는다`() {
        val author = userRepository.save(
            User(nickname = "글쓴이", profileImageUrl = "https://example.com/p.png", status = UserStatus.ACTIVE),
        )
        val story = storyRepository.save(
            Story(
                title = "작성자 있는 스토리",
                userId = author.id,
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.author.nickname").isEqualTo("글쓴이")
            .jsonPath("$.author.profileImageUrl").isEqualTo("https://example.com/p.png")
            // 내부 PK 비노출 원칙: author.id는 항상 null이다.
            .jsonPath("$.author.id").isEmpty

        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body("""{"storyIds":["${story.publicId}"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].author.nickname").isEqualTo("글쓴이")
            .jsonPath("$[0].author.id").isEmpty
    }

    @Test
    fun `상세 isOwner는 소유자만 true고 타인·게스트는 false다`() {
        val owner = userRepository.save(User(nickname = "소유자", status = UserStatus.ACTIVE))
        val other = userRepository.save(User(nickname = "타인", status = UserStatus.ACTIVE))
        val story = storyRepository.save(
            Story(
                title = "소유 판단 스토리",
                userId = owner.id,
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(owner.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isOwner").isEqualTo(true)

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(other.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isOwner").isEqualTo(false)

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isOwner").isEqualTo(false)
    }

    @Test
    fun `게스트 스토리는 게스트 요청에도 isOwner false다`() {
        val story = storyRepository.save(Story(title = "무소유 스토리"))

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isOwner").isEqualTo(false)
            // 작성자 없는 스토리의 author는 계속 null이다.
            .jsonPath("$.author").isEmpty
    }

    @Test
    fun `turnCount는 스토리의 미삭제 채팅 current_turn 합이다`() {
        val story = storyRepository.save(Story(title = "턴 집계 스토리", genre = "판타지", visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC, status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED))
        storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 3))
        storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 2))
        // 소프트 삭제된 채팅은 합산에서 제외한다.
        storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 5, deletedAt = Instant.now()))

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.turnCount").isEqualTo(5)
    }

    @Test
    fun `스토리 상세 정보를 조회한다`() {
        val story = storyRepository.save(
            Story(
                title = "잿빛 왕관",
                oneLineIntro = "무너진 왕국에서 진실을 좇는다.",
                description = "역병과 반란으로 무너진 아르덴 왕국 이야기.",
                genre = "다크 판타지, 정치극",
            ),
        )
        val startSetting = storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "선왕의 장례식 날",
                prologue = "잿빛 비가 사흘째 왕성을 적신다.",
                startSituation = "장례식이 끝난 늦은 밤, 기사단 숙소.",
            ),
        )
        storySuggestedInputRepository.saveAll(
            listOf(
                StorySuggestedInput(startSetting = startSetting, inputText = "레이에게 문을 열어준다", inputOrder = 1.toShort()),
                StorySuggestedInput(startSetting = startSetting, inputText = "경계하며 누구냐고 묻는다", inputOrder = 2.toShort()),
                StorySuggestedInput(startSetting = startSetting, inputText = "침묵한다", inputOrder = 3.toShort()),
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // 응답 id는 순차 PK가 아니라 추측 불가능한 공개 식별자(public_id)다.
            .jsonPath("$.id").isEqualTo(story.publicId.toString())
            .jsonPath("$.title").isEqualTo("잿빛 왕관")
            .jsonPath("$.oneLineIntro").isEqualTo("무너진 왕국에서 진실을 좇는다.")
            .jsonPath("$.description").isEqualTo("역병과 반란으로 무너진 아르덴 왕국 이야기.")
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.genres[0]").isEqualTo("다크 판타지")
            .jsonPath("$.genres[1]").isEqualTo("정치극")
            .jsonPath("$.startSettings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].id").isEqualTo(startSetting.publicId.toString())
            .jsonPath("$.startSettings[0].name").isEqualTo("선왕의 장례식 날")
            .jsonPath("$.startSettings[0].prologue").isEqualTo("잿빛 비가 사흘째 왕성을 적신다.")
            .jsonPath("$.startSettings[0].startSituation").isEqualTo("장례식이 끝난 늦은 밤, 기사단 숙소.")
            .jsonPath("$.startSettings[0].suggestedInputs.length()").isEqualTo(3)
            .jsonPath("$.startSettings[0].suggestedInputs[0]").isEqualTo("레이에게 문을 열어준다")
            .jsonPath("$.startSettings[0].suggestedInputs[2]").isEqualTo("침묵한다")
            .jsonPath("$.thumbnailUrl").isEmpty
            .jsonPath("$.author").isEmpty
            .jsonPath("$.hashtags.length()").isEqualTo(0)
            .jsonPath("$.turnCount").isEqualTo(0)
            .jsonPath("$.likeCount").isEqualTo(0)
            .jsonPath("$.visibility").isEqualTo("PUBLIC")
            .jsonPath("$.status").isEqualTo("PUBLISHED")
    }

    @Test
    fun `존재하지 않는 스토리는 404로 응답한다`() {
        restTestClient.get()
            .uri("/api/v1/stories/999999")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("스토리를 찾을 수 없습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/999999")
    }

    @Test
    fun `순차 PK(내부 id)로는 조회되지 않고 404로 통일된다 (IDOR 차단)`() {
        val story = storyRepository.save(Story(title = "공개 식별자만 노출하는 스토리"))

        // 내부 순차 PK를 추측해 접근해도 공개 식별자(UUID)가 아니므로 404로 통일된다.
        restTestClient.get()
            .uri("/api/v1/stories/${story.id}")
            .exchange()
            .expectStatus().isNotFound

        // 공개 식별자로는 정상 조회된다.
        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `시작 설정이 없는 스토리도 빈 시작 정보로 조회된다`() {
        val story = storyRepository.save(
            Story(
                title = "설정 미완 스토리",
                oneLineIntro = "한 줄 소개",
                description = null,
                genre = null,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.title").isEqualTo("설정 미완 스토리")
            .jsonPath("$.genres.length()").isEqualTo(0)
            .jsonPath("$.startSettings.length()").isEqualTo(0)
    }

    @Test
    fun `시작 설정이 여러 개면 등록 순서로 싣고 추천 입력·엔딩을 각 시작 설정에 종속시킨다`() {
        val story = storyRepository.save(
            Story(
                title = "복수 시작 설정",
                genre = "판타지",
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED,
            ),
        )
        val first = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "첫 시작", prologue = "프롤로그1"))
        val second = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "둘째 시작", prologue = "프롤로그2"))
        storySuggestedInputRepository.saveAll(
            listOf(
                StorySuggestedInput(startSetting = first, inputText = "가", inputOrder = 1.toShort()),
                StorySuggestedInput(startSetting = second, inputText = "나", inputOrder = 1.toShort()),
                StorySuggestedInput(startSetting = second, inputText = "다", inputOrder = 2.toShort()),
            ),
        )
        storyEndingRepository.save(
            StoryEnding(startSetting = second, name = "둘째의 엔딩", minTurns = 3, achievementCondition = "조건", epilogue = "에필로그", sortOrder = 1),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // 등록 순서(id 오름차순)로 실린다.
            .jsonPath("$.startSettings.length()").isEqualTo(2)
            .jsonPath("$.startSettings[0].id").isEqualTo(first.publicId.toString())
            .jsonPath("$.startSettings[0].name").isEqualTo("첫 시작")
            .jsonPath("$.startSettings[0].suggestedInputs.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].endings.length()").isEqualTo(0)
            .jsonPath("$.startSettings[1].id").isEqualTo(second.publicId.toString())
            .jsonPath("$.startSettings[1].name").isEqualTo("둘째 시작")
            // 추천 입력·엔딩은 각 시작 설정 스코프로 정확히 매핑된다.
            .jsonPath("$.startSettings[1].suggestedInputs.length()").isEqualTo(2)
            .jsonPath("$.startSettings[1].endings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[1].endings[0].name").isEqualTo("둘째의 엔딩")
    }

    @Test
    /** 인물과 그 인물의 `{이름}_기본` 이미지를 함께 심는다(KNK-1126 — 대표 이미지 정본). */
    private fun seedCharacterWithImage(
        story: com.knk.manyak.story.entity.Story,
        name: String,
        imageUrl: String,
        gender: String? = null,
    ) {
        val character = storyCharacterRepository.save(
            StoryCharacter(story = story, name = name, imageUrl = imageUrl, gender = gender),
        )
        storyCharacterImageRepository.save(
            com.knk.manyak.story.entity.StoryCharacterImage(
                character = character,
                imageName = com.knk.manyak.story.entity.StoryCharacterImage.defaultImageNameOf(name),
                imageUrl = imageUrl,
            ),
        )
    }

    @Test
    fun `상세 대표 이미지는 기본 이름을 우선하고 없으면 첫 장이다`() {
        // KNK-1126: 인물당 여러 장이 되면서 카드에 쓸 한 장을 정해야 한다. 컴파일이 만든 첫 장이 `_기본`이라 그것을 쓴다.
        val story = storyRepository.save(
            Story(
                title = "대표 이미지 스토리",
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED,
            ),
        )
        val character = storyCharacterRepository.save(StoryCharacter(story = story, name = "레이"))
        // 표시 순서로는 웃음이 먼저지만 대표는 `_기본`이어야 한다.
        storyCharacterImageRepository.save(
            com.knk.manyak.story.entity.StoryCharacterImage(
                character = character,
                imageName = "레이_웃음",
                imageUrl = "https://cdn.manyak.app/characters/uploaded/smile.webp",
                sortOrder = 0,
            ),
        )
        storyCharacterImageRepository.save(
            com.knk.manyak.story.entity.StoryCharacterImage(
                character = character,
                imageName = "레이_기본",
                imageUrl = "https://cdn.manyak.app/characters/ray.png",
                sortOrder = 1,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.characters[0].imageUrl").isEqualTo("https://cdn.manyak.app/characters/ray.png")
    }

    @Test
    fun `상세 characters는 저장 순서로 실리고 이미지 없는 인물도 imageUrl null로 포함한다`() {
        val story = storyRepository.save(
            Story(
                title = "인물 있는 스토리",
                visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC,
                status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED,
            ),
        )
        // 저장 순서(= 컴파일 응답 순서)를 보존하는지 보려고 이미지 있는 인물과 없는 인물을 섞어 넣는다.
        // 대표 이미지의 정본은 story_character_images다(KNK-1126, V76) — 컴파일이 만든 첫 장은 `{이름}_기본`이다.
        seedCharacterWithImage(story, "레이", "https://cdn.manyak.app/characters/ray.png", gender = "남성")
        storyCharacterRepository.save(StoryCharacter(story = story, name = "카일", imageUrl = null))
        seedCharacterWithImage(story, "미라", "https://cdn.manyak.app/characters/mira.png")

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.characters.length()").isEqualTo(3)
            .jsonPath("$.characters[0].name").isEqualTo("레이")
            .jsonPath("$.characters[0].imageUrl").isEqualTo("https://cdn.manyak.app/characters/ray.png")
            // 이미지 생성에 실패한 인물도 목록에서 빠지지 않고 imageUrl만 null이다.
            .jsonPath("$.characters[1].name").isEqualTo("카일")
            .jsonPath("$.characters[1].imageUrl").isEmpty
            .jsonPath("$.characters[2].name").isEqualTo("미라")
            .jsonPath("$.characters[2].imageUrl").isEqualTo("https://cdn.manyak.app/characters/mira.png")
            // 인물 공개 식별자·외형 필드는 상세 응답에 노출하지 않는다.
            .jsonPath("$.characters[0].id").doesNotExist()
            .jsonPath("$.characters[0].gender").doesNotExist()
    }

    @Test
    fun `인물 행이 없는 스토리의 characters는 빈 배열이다`() {
        val story = storyRepository.save(Story(title = "인물 없는 스토리"))

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.characters.length()").isEqualTo(0)
    }
}
