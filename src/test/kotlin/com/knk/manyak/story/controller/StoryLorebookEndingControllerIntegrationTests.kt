package com.knk.manyak.story.controller

import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.AfterEach
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
class StoryLorebookEndingControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var lorebookRepository: LorebookRepository

    @Autowired
    private lateinit var storyLorebookRepository: StoryLorebookRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storyEndingRepository: StoryEndingRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    // 테스트 프로파일은 Flyway가 꺼져 있어(Hibernate DDL) 마이그레이션의 ON DELETE CASCADE가 없다.
    // 같은 JVM의 다른 테스트가 커밋해둔 기존 자식 행이 남아 있으면 stories 삭제가 FK 위반으로 실패하므로,
    // 기존 자식 테이블(추천 입력 → 시작 설정 → 스토리 설정)까지 자식→부모 순으로 비운다.
    @BeforeEach
    fun setUp() {
        cleanAll()
    }

    // @SpringBootTest는 트랜잭션 롤백이 없어 마지막 테스트가 커밋한 행이 그대로 남는다. 남은 story_lorebooks/
    // story_endings 행은 새 테이블을 정리하지 않는 기존 통합 테스트의 stories 삭제를 FK 위반으로 깨뜨리므로,
    // 실행 후에도 동일하게 비워 공유 H2에 잔여물을 남기지 않는다.
    @AfterEach
    fun tearDown() {
        cleanAll()
    }

    private fun cleanAll() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `스토리 상세에 참조 로어북과 엔딩이 순서대로 포함된다`() {
        val story = storyRepository.save(Story(title = "잿빛 왕관", genre = "다크 판타지"))
        val startSetting = storyStartSettingRepository.save(StoryStartSetting(story = story, name = "시작 설정"))
        val worldGlossary = lorebookRepository.save(Lorebook(name = "왕국 용어집", genre = "다크 판타지", content = "아르덴: 몰락한 왕국"))
        val magicGlossary = lorebookRepository.save(Lorebook(name = "마법 용어집", genre = "다크 판타지", content = "계약술: 피로 맺는 마법"))
        // 삽입 역순으로 저장해 sort_order 정렬을 검증한다.
        storyLorebookRepository.save(StoryLorebook(story = story, lorebook = magicGlossary, sortOrder = 2))
        storyLorebookRepository.save(StoryLorebook(story = story, lorebook = worldGlossary, sortOrder = 1))
        storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "배드 엔딩", minTurns = 5, achievementCondition = "왕국은 무너진다", epilogue = "잿더미 위에 홀로 선다", sortOrder = 2),
        )
        storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "해피 엔딩", minTurns = 10, achievementCondition = "왕좌를 되찾는다", epilogue = "대관식을 연다", sortOrder = 1),
        )
        // 비활성(레거시 보존) 엔딩은 상세 조회에 나타나지 않는다(§4-3-10).
        storyEndingRepository.save(
            StoryEnding(startSetting = startSetting, name = "레거시", minTurns = 0, achievementCondition = "-", epilogue = "-", sortOrder = 3, enabled = false),
        )

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.lorebooks.length()").isEqualTo(2)
            .jsonPath("$.lorebooks[0].name").isEqualTo("왕국 용어집")
            .jsonPath("$.lorebooks[0].genre").isEqualTo("다크 판타지")
            .jsonPath("$.lorebooks[0].content").isEqualTo("아르덴: 몰락한 왕국")
            .jsonPath("$.lorebooks[1].name").isEqualTo("마법 용어집")
            .jsonPath("$.endings.length()").isEqualTo(2)
            .jsonPath("$.endings[0].name").isEqualTo("해피 엔딩")
            .jsonPath("$.endings[0].requirement.minTurns").isEqualTo(10)
            .jsonPath("$.endings[0].requirement.achievementCondition").isEqualTo("왕좌를 되찾는다")
            .jsonPath("$.endings[0].epilogue").isEqualTo("대관식을 연다")
            .jsonPath("$.endings[1].name").isEqualTo("배드 엔딩")
            .jsonPath("$.endings[1].requirement.minTurns").isEqualTo(5)
    }

    @Test
    fun `로어북과 엔딩이 없는 스토리는 빈 배열로 조회된다`() {
        val story = storyRepository.save(Story(title = "설정 미완 스토리"))

        restTestClient.get()
            .uri("/api/v1/stories/${story.publicId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.lorebooks.length()").isEqualTo(0)
            .jsonPath("$.endings.length()").isEqualTo(0)
    }

    @Test
    fun `로어북 카탈로그를 장르로 필터해 조회한다`() {
        lorebookRepository.save(Lorebook(name = "무협 용어집", genre = "무협", content = "내공: 무공의 근원", sortOrder = 1))
        lorebookRepository.save(Lorebook(name = "판타지 용어집", genre = "판타지", content = "마나: 마법의 에너지", sortOrder = 1))
        lorebookRepository.save(Lorebook(name = "비활성 무협", genre = "무협", content = "c", sortOrder = 2, isActive = false))

        restTestClient.get()
            .uri("/api/v1/stories/lorebooks?genre=무협")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].name").isEqualTo("무협 용어집")
            .jsonPath("$[0].genre").isEqualTo("무협")
    }

    @Test
    fun `로어북 카탈로그 전체를 조회한다`() {
        lorebookRepository.save(Lorebook(name = "무협 용어집", genre = "무협", content = "c", sortOrder = 1))
        lorebookRepository.save(Lorebook(name = "판타지 용어집", genre = "판타지", content = "c", sortOrder = 1))

        restTestClient.get()
            .uri("/api/v1/stories/lorebooks")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
    }
}
