package com.knk.manyak.story.service

import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.image.service.RandomIndexPicker
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryImageRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 스토리 등록 시 채팅 배경 후보를 확정해 연결하는 규칙을 검증한다(스펙 §4-3-9).
 *
 * 썸네일 자동 연결과 달리 **장르 무관 폴백이 없다** — 매칭 0건이면 후보 없이 두고 이미지 없는 채팅이 된다.
 * 랜덤 선택은 [RandomIndexPicker]로 주입해 결정적으로 만든다(항상 남은 후보의 첫 항목).
 */
@ActiveProfiles("test")
@SpringBootTest
class StoryBackgroundImageLinkerIntegrationTests {

    @Autowired private lateinit var imagePresetRepository: ImagePresetRepository
    @Autowired private lateinit var storyImageRepository: StoryImageRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyCreationTagRepository: StoryCreationTagRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private lateinit var linker: StoryBackgroundImageLinker
    private var storyId: Long = 0

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        linker = StoryBackgroundImageLinker(imagePresetRepository, storyImageRepository, RandomIndexPicker { 0 })
        storyId = storyRepository.save(Story(title = "달빛 아래의 계약")).id
    }

    @Test
    fun `첫 번째 장르와 일치하는 배경을 후보로 연결한다`() {
        val martialArts = saveGenreTag("무협")
        val romance = saveGenreTag("로맨스 판타지")
        savePreset("bg_0001", ImagePresetType.BACKGROUND, romance)
        savePreset("bg_0002", ImagePresetType.BACKGROUND, martialArts)
        savePreset("bg_0003", ImagePresetType.BACKGROUND, martialArts)

        linker.linkFor(storyId, listOf("무협"))

        assertThat(linkedKeys()).containsExactly("bg_0002", "bg_0003")
    }

    /** 썸네일과 다른 지점: 매칭이 없으면 장르 무관으로 채우지 않는다. */
    @Test
    fun `장르 매칭이 없으면 후보를 연결하지 않는다`() {
        val romance = saveGenreTag("로맨스 판타지")
        savePreset("bg_0001", ImagePresetType.BACKGROUND, romance)

        linker.linkFor(storyId, listOf("아포칼립스"))

        assertThat(linkedKeys()).isEmpty()
    }

    @Test
    fun `장르가 없는 스토리는 후보를 연결하지 않는다`() {
        savePreset("bg_0001", ImagePresetType.BACKGROUND)

        linker.linkFor(storyId, emptyList())

        assertThat(linkedKeys()).isEmpty()
    }

    @Test
    fun `비활성 배경은 후보에서 제외된다`() {
        val martialArts = saveGenreTag("무협")
        savePreset("bg_0001", ImagePresetType.BACKGROUND, martialArts, deactivatedAt = Instant.now())
        savePreset("bg_0002", ImagePresetType.BACKGROUND, martialArts)

        linker.linkFor(storyId, listOf("무협"))

        assertThat(linkedKeys()).containsExactly("bg_0002")
    }

    @Test
    fun `썸네일·캐릭터는 배경 후보가 아니다`() {
        val martialArts = saveGenreTag("무협")
        savePreset("thumb_0001", ImagePresetType.THUMBNAIL, martialArts)
        savePreset("char_0001", ImagePresetType.CHARACTER, martialArts)

        linker.linkFor(storyId, listOf("무협"))

        assertThat(linkedKeys()).isEmpty()
    }

    /** 실물 자산이 장르별로 얇아 최소 개수는 강제하지 않는다 — 상한만 지킨다. */
    @Test
    fun `후보가 상한을 넘으면 상한만큼만 연결한다`() {
        val martialArts = saveGenreTag("무협")
        repeat(StoryBackgroundImageLinker.MAX_CANDIDATES + 3) { i ->
            savePreset("bg_%04d".format(i + 1), ImagePresetType.BACKGROUND, martialArts)
        }

        linker.linkFor(storyId, listOf("무협"))

        assertThat(linkedKeys()).hasSize(StoryBackgroundImageLinker.MAX_CANDIDATES)
    }

    @Test
    fun `후보는 중복 없이 연결된다`() {
        val martialArts = saveGenreTag("무협")
        savePreset("bg_0001", ImagePresetType.BACKGROUND, martialArts)
        savePreset("bg_0002", ImagePresetType.BACKGROUND, martialArts)

        linker.linkFor(storyId, listOf("무협"))

        assertThat(linkedKeys()).doesNotHaveDuplicates().hasSize(2)
    }

    private fun linkedKeys(): List<String> =
        storyImageRepository.findByStoryIdOrderByIdAsc(storyId).map { it.imageKey }

    private fun saveGenreTag(name: String): StoryCreationTag =
        storyCreationTagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = name,
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = 10,
            ),
        )

    private fun savePreset(
        imageKey: String,
        type: ImagePresetType,
        vararg genres: StoryCreationTag,
        deactivatedAt: Instant? = null,
    ): ImagePreset =
        imagePresetRepository.save(
            ImagePreset(imageKey = imageKey, type = type, genres = genres.toSet(), deactivatedAt = deactivatedAt),
        )
}
