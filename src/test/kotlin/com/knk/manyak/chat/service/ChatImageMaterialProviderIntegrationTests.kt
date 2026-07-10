package com.knk.manyak.chat.service

import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryImage
import com.knk.manyak.story.repository.StoryCharacterRepository
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
 * 매 턴 AI 요청에 실을 이미지 재료를 모으는 규칙을 검증한다(스펙 §4-3-9).
 *
 * 배경은 등록 시 확정한 후보를 같은 순서로 전달하고, 캐릭터는 컴파일이 고정한 매핑을 그대로 되돌려 싣는다.
 * 비활성 이미지는 양쪽 모두 전달에서 제외된다 — 내린 다음 턴부터 노출이 멈춰야 하기 때문이다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ChatImageMaterialProviderIntegrationTests {

    @Autowired private lateinit var provider: ChatImageMaterialProvider
    @Autowired private lateinit var imagePresetRepository: ImagePresetRepository
    @Autowired private lateinit var storyImageRepository: StoryImageRepository
    @Autowired private lateinit var storyCharacterRepository: StoryCharacterRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private var storyId: Long = 0

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        storyId = storyRepository.save(Story(title = "달빛 아래의 계약")).id
    }

    @Test
    fun `배경 후보를 연결 순서대로 의미 태그와 함께 싣는다`() {
        savePreset("bg_0002", ImagePresetType.BACKGROUND, mood = "비밀스러운", subject = "심야카페", prop = "튤립꽃다발")
        savePreset("bg_0001", ImagePresetType.BACKGROUND, mood = "긴장", subject = "던전로비", prop = "포탈")
        linkBackground("bg_0002")
        linkBackground("bg_0001")

        val candidates = provider.backgroundCandidates(storyId)

        assertThat(candidates.map { it.imageKey }).containsExactly("bg_0002", "bg_0001")
        assertThat(candidates.first().mood).isEqualTo("비밀스러운")
        assertThat(candidates.first().place).isEqualTo("심야카페")
        assertThat(candidates.first().prop).isEqualTo("튤립꽃다발")
    }

    @Test
    fun `비활성 배경은 전달에서 제외된다`() {
        savePreset("bg_0001", ImagePresetType.BACKGROUND, deactivatedAt = Instant.now())
        savePreset("bg_0002", ImagePresetType.BACKGROUND)
        linkBackground("bg_0001")
        linkBackground("bg_0002")

        assertThat(provider.backgroundCandidates(storyId).map { it.imageKey }).containsExactly("bg_0002")
    }

    @Test
    fun `후보가 없는 스토리는 빈 목록이다`() {
        assertThat(provider.backgroundCandidates(storyId)).isEmpty()
        assertThat(provider.characterImages(storyId)).isEmpty()
    }

    @Test
    fun `인물 매핑을 이름과 키로 싣는다`() {
        savePreset("char_0031", ImagePresetType.CHARACTER)
        saveCharacter("이서린", "char_0031")

        val images = provider.characterImages(storyId)

        assertThat(images).singleElement().satisfies({
            assertThat(it.name).isEqualTo("이서린")
            assertThat(it.imageKey).isEqualTo("char_0031")
        })
    }

    /** 배정 실패 인물은 이미지 없이 진행한다(graceful). */
    @Test
    fun `이미지가 배정되지 않은 인물은 전달에서 빠진다`() {
        savePreset("char_0031", ImagePresetType.CHARACTER)
        saveCharacter("이서린", "char_0031")
        saveCharacter("백무현", null)

        assertThat(provider.characterImages(storyId).map { it.name }).containsExactly("이서린")
    }

    @Test
    fun `비활성 인물 이미지는 전달에서 제외된다`() {
        savePreset("char_0031", ImagePresetType.CHARACTER, deactivatedAt = Instant.now())
        saveCharacter("이서린", "char_0031")

        assertThat(provider.characterImages(storyId)).isEmpty()
    }

    private fun linkBackground(imageKey: String) =
        storyImageRepository.save(StoryImage(storyId = storyId, imageKey = imageKey))

    private fun saveCharacter(name: String, imageKey: String?) =
        storyCharacterRepository.save(StoryCharacter(storyId = storyId, name = name, imageKey = imageKey))

    private fun savePreset(
        imageKey: String,
        type: ImagePresetType,
        mood: String? = null,
        subject: String? = null,
        prop: String? = null,
        deactivatedAt: Instant? = null,
    ): ImagePreset =
        imagePresetRepository.save(
            ImagePreset(
                imageKey = imageKey,
                type = type,
                mood = mood,
                subject = subject,
                prop = prop,
                deactivatedAt = deactivatedAt,
            ),
        )
}
