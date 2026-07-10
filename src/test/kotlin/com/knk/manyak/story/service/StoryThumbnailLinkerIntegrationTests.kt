package com.knk.manyak.story.service

import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.image.service.RandomIndexPicker
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 스토리 등록 시 썸네일 자동 연결 규칙을 검증한다(스펙 §4-3-9).
 *
 * 테스트 프로파일은 Flyway가 꺼져 있어(`ddl-auto`) 시드 마이그레이션이 실행되지 않는다.
 * 카탈로그를 픽스처로 심지 않으면 "후보 없음 → NULL 폴백" 경로만 검증되므로, 각 시나리오가
 * 필요한 프리셋을 직접 저장한다.
 *
 * 랜덤 선택은 [RandomIndexPicker]로 주입해 결정적으로 만든다(항상 후보 목록의 첫 항목).
 */
@ActiveProfiles("test")
@SpringBootTest
class StoryThumbnailLinkerIntegrationTests {

    @Autowired
    private lateinit var imagePresetRepository: ImagePresetRepository

    @Autowired
    private lateinit var storyCreationTagRepository: StoryCreationTagRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    private lateinit var linker: StoryThumbnailLinker

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        linker = StoryThumbnailLinker(imagePresetRepository, RandomIndexPicker { 0 })
    }

    @Test
    fun `첫 번째 장르와 일치하는 썸네일을 연결한다`() {
        val martialArts = saveGenreTag("무협")
        val romance = saveGenreTag("로맨스 판타지")
        savePreset("thumb_0001", ImagePresetType.THUMBNAIL, romance)
        savePreset("thumb_0002", ImagePresetType.THUMBNAIL, martialArts)

        val linked = linker.linkFor(listOf("무협"))

        assertThat(linked).isEqualTo("thumb_0002")
    }

    @Test
    fun `장르가 여러 개면 첫 번째 장르만 매칭에 쓴다`() {
        val martialArts = saveGenreTag("무협")
        val romance = saveGenreTag("로맨스 판타지")
        savePreset("thumb_0001", ImagePresetType.THUMBNAIL, romance)
        savePreset("thumb_0002", ImagePresetType.THUMBNAIL, martialArts)

        val linked = linker.linkFor(listOf("무협", "로맨스 판타지"))

        assertThat(linked).isEqualTo("thumb_0002")
    }

    @Test
    fun `장르 매칭이 없으면 장르 무관 썸네일로 폴백한다`() {
        val romance = saveGenreTag("로맨스 판타지")
        savePreset("thumb_0001", ImagePresetType.THUMBNAIL, romance)

        val linked = linker.linkFor(listOf("아포칼립스"))

        assertThat(linked).isEqualTo("thumb_0001")
    }

    @Test
    fun `장르가 없는 스토리도 장르 무관 폴백으로 연결된다`() {
        savePreset("thumb_0001", ImagePresetType.THUMBNAIL)

        val linked = linker.linkFor(emptyList())

        assertThat(linked).isEqualTo("thumb_0001")
    }

    @Test
    fun `비활성 이미지는 매칭에서도 폴백에서도 제외된다`() {
        val martialArts = saveGenreTag("무협")
        savePreset("thumb_0001", ImagePresetType.THUMBNAIL, martialArts, deactivatedAt = Instant.now())

        val linked = linker.linkFor(listOf("무협"))

        assertThat(linked).isNull()
    }

    @Test
    fun `배경·캐릭터 이미지는 썸네일 후보가 아니다`() {
        val martialArts = saveGenreTag("무협")
        savePreset("bg_0001", ImagePresetType.BACKGROUND, martialArts)
        savePreset("char_0001", ImagePresetType.CHARACTER, martialArts)

        val linked = linker.linkFor(listOf("무협"))

        assertThat(linked).isNull()
    }

    @Test
    fun `후보가 하나도 없으면 null이다`() {
        val linked = linker.linkFor(listOf("무협"))

        assertThat(linked).isNull()
    }

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
            ImagePreset(
                imageKey = imageKey,
                type = type,
                genres = genres.toSet(),
                deactivatedAt = deactivatedAt,
            ),
        )
}
