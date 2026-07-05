package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryLorebook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
class LorebookEndingRepositoryTests {

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var lorebookRepository: LorebookRepository

    @Autowired
    private lateinit var storyLorebookRepository: StoryLorebookRepository

    @Autowired
    private lateinit var storyEndingRepository: StoryEndingRepository

    @Autowired
    private lateinit var storyMainEventRepository: StoryMainEventRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    // 테스트용 H2(jdbc:h2:mem:manyak-test)는 컨텍스트 간 공유된다. @SpringBootTest가 커밋한 로어북 카탈로그 행이
    // 남아 전역 카탈로그 조회를 오염시키지 않도록, 트랜잭션 안에서 자식→부모 순으로 비운다(테스트 종료 시 롤백).
    // Hibernate DDL에는 ON DELETE CASCADE가 없어, 기존 자식 테이블(추천 입력 → 시작 설정 → 스토리 설정)도
    // 먼저 비워야 stories 삭제가 FK 위반으로 실패하지 않는다.
    @BeforeEach
    fun setUp() {
        storySuggestedInputRepository.deleteAllInBatch()
        storyStartSettingRepository.deleteAllInBatch()
        storySettingRepository.deleteAllInBatch()
        storyLorebookRepository.deleteAllInBatch()
        storyEndingRepository.deleteAllInBatch()
        storyMainEventRepository.deleteAllInBatch()
        lorebookRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
    }

    private fun newStory(): Story = storyRepository.save(Story(title = "잿빛 왕관"))

    @Test
    fun `로어북 저장 시 기본값 is_active=true, sort_order=0이 적용된다`() {
        val saved = lorebookRepository.save(Lorebook(name = "무협 용어집", genre = "무협", content = "내공: 무공의 근원이 되는 기운"))

        val found = lorebookRepository.findById(saved.id).orElseThrow()
        assertTrue(found.isActive)
        assertEquals(0, found.sortOrder)
    }

    @Test
    fun `활성 로어북을 장르로 필터해 정렬 조회한다`() {
        lorebookRepository.save(Lorebook(name = "판타지B", genre = "판타지", content = "c", sortOrder = 2))
        lorebookRepository.save(Lorebook(name = "판타지A", genre = "판타지", content = "c", sortOrder = 1))
        lorebookRepository.save(Lorebook(name = "무협", genre = "무협", content = "c", sortOrder = 1))
        lorebookRepository.save(Lorebook(name = "비활성", genre = "판타지", content = "c", sortOrder = 0, isActive = false))

        val fantasy = lorebookRepository.findByGenreAndIsActiveTrueOrderBySortOrderAscIdAsc("판타지")

        assertEquals(listOf("판타지A", "판타지B"), fantasy.map { it.name })
    }

    @Test
    fun `id 목록으로 활성 로어북만 조회한다`() {
        val active = lorebookRepository.save(Lorebook(name = "활성", content = "c"))
        val inactive = lorebookRepository.save(Lorebook(name = "비활성", content = "c", isActive = false))

        val found = lorebookRepository.findByIdInAndIsActiveTrue(listOf(active.id, inactive.id))

        assertEquals(listOf(active.id), found.map { it.id })
    }

    @Test
    fun `스토리가 참조하는 로어북을 sort_order 순으로 조회한다`() {
        val story = newStory()
        val first = lorebookRepository.save(Lorebook(name = "L1", content = "c"))
        val second = lorebookRepository.save(Lorebook(name = "L2", content = "c"))
        storyLorebookRepository.save(StoryLorebook(story = story, lorebook = second, sortOrder = 2))
        storyLorebookRepository.save(StoryLorebook(story = story, lorebook = first, sortOrder = 1))

        val refs = storyLorebookRepository.findByStoryIdOrderBySortOrderAscIdAsc(story.id)

        assertEquals(listOf("L1", "L2"), refs.map { it.lorebook.name })
    }

    @Test
    fun `같은 스토리가 동일 로어북을 중복 참조하면 UNIQUE 제약으로 거부된다`() {
        val story = newStory()
        val lorebook = lorebookRepository.save(Lorebook(name = "L", content = "c"))
        storyLorebookRepository.saveAndFlush(StoryLorebook(story = story, lorebook = lorebook, sortOrder = 1))

        assertThrows(DataIntegrityViolationException::class.java) {
            storyLorebookRepository.saveAndFlush(StoryLorebook(story = story, lorebook = lorebook, sortOrder = 2))
        }
    }

    @Test
    fun `스토리 엔딩을 sort_order 순으로 조회하고 기본값 enabled=true가 적용된다`() {
        val story = newStory()
        storyEndingRepository.save(StoryEnding(story = story, title = "배드 엔딩", content = "몰락한다", sortOrder = 2))
        storyEndingRepository.save(
            StoryEnding(story = story, title = "해피 엔딩", content = "왕좌에 오른다", conditionText = "신뢰도 100 이상", sortOrder = 1),
        )

        val endings = storyEndingRepository.findByStoryIdOrderBySortOrderAsc(story.id)

        assertEquals(listOf("해피 엔딩", "배드 엔딩"), endings.map { it.title })
        assertTrue(endings.all { it.enabled })
        assertEquals("신뢰도 100 이상", endings.first().conditionText)
        assertNull(endings.last().conditionText)
    }

    @Test
    fun `같은 스토리에 동일 sort_order 엔딩은 UNIQUE 제약으로 거부된다`() {
        val story = newStory()
        storyEndingRepository.saveAndFlush(StoryEnding(story = story, title = "A", content = "...", sortOrder = 1))

        assertThrows(DataIntegrityViolationException::class.java) {
            storyEndingRepository.saveAndFlush(StoryEnding(story = story, title = "B", content = "...", sortOrder = 1))
        }
    }
}
