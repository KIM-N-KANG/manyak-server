package com.knk.manyak.chat.service

import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Instant

/**
 * AI 본문의 이미지 마커를 검증해 `images[]`를 구성하는 규칙을 확인한다(스펙 §4-3-9).
 *
 * 백엔드 검증은 세 겹이다: 카탈로그 키 필터 · 비활성 제외 · 타입별 턴당 1장 상한(등장 순서상 첫 장).
 * 본문은 어떤 경우에도 바뀌지 않는다 — 걸러진 마커는 프론트엔드가 텍스트째 숨긴다.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestPropertySource(properties = ["manyak.asset.image-base-url=https://cdn.test"])
class ChatImageBundlerIntegrationTests {

    @Autowired private lateinit var bundler: ChatImageBundler
    @Autowired private lateinit var imagePresetRepository: ImagePresetRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `마커가 없으면 빈 목록이다`() {
        savePreset("bg_0001", ImagePresetType.BACKGROUND)

        assertThat(bundler.bundle("문이 열리자 붉은 노을이 번졌다.")).isEmpty()
    }

    @Test
    fun `카탈로그에 있는 키만 URL과 타입을 붙여 싣는다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND)

        val images = bundler.bundle("노을이 번졌다.\n[[image:bg_0007]]\n그녀가 뒤를 돌아보았다.")

        assertThat(images).singleElement().satisfies({
            assertThat(it.imageKey).isEqualTo("bg_0007")
            assertThat(it.type).isEqualTo(ImagePresetType.BACKGROUND)
            assertThat(it.url).isEqualTo("https://cdn.test/backgrounds/bg_0007.png")
        })
    }

    /** AI가 후보 밖 키나 오타 키를 뱉어도 images[]에는 실리지 않는다(본문 마커는 그대로 남는다). */
    @Test
    fun `카탈로그에 없는 키는 걸러진다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND)

        val images = bundler.bundle("[[image:bg_0007]] [[image:bg_9999]]")

        assertThat(images.map { it.imageKey }).containsExactly("bg_0007")
    }

    @Test
    fun `비활성 이미지는 걸러진다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND, deactivatedAt = Instant.now())

        assertThat(bundler.bundle("[[image:bg_0007]]")).isEmpty()
    }

    @Test
    fun `타입별로 등장 순서상 첫 장만 남는다`() {
        savePreset("bg_0001", ImagePresetType.BACKGROUND)
        savePreset("bg_0002", ImagePresetType.BACKGROUND)
        savePreset("char_0001", ImagePresetType.CHARACTER)
        savePreset("char_0002", ImagePresetType.CHARACTER)

        val images = bundler.bundle("[[image:bg_0001]] [[image:char_0001]] [[image:bg_0002]] [[image:char_0002]]")

        assertThat(images.map { it.imageKey }).containsExactly("bg_0001", "char_0001")
    }

    @Test
    fun `같은 키가 두 번 나와도 한 번만 실린다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND)

        val images = bundler.bundle("[[image:bg_0007]] ... [[image:bg_0007]]")

        assertThat(images.map { it.imageKey }).containsExactly("bg_0007")
    }

    /** 채팅 본문에 실리는 타입은 BACKGROUND·CHARACTER뿐이다(§4-3-9). 썸네일 키 마커는 무효다. */
    @Test
    fun `썸네일 키 마커는 걸러진다`() {
        savePreset("thumb_0001", ImagePresetType.THUMBNAIL)

        assertThat(bundler.bundle("[[image:thumb_0001]]")).isEmpty()
    }

    @Test
    fun `문법이 깨진 마커는 추출되지 않는다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND)

        // 대문자 키·공백·닫힘 누락은 마커 문법이 아니다.
        assertThat(bundler.bundle("[[image:BG_0007]] [[image: bg_0007]] [[image:bg_0007]")).isEmpty()
    }

    private fun savePreset(
        imageKey: String,
        type: ImagePresetType,
        deactivatedAt: Instant? = null,
    ): ImagePreset =
        imagePresetRepository.save(ImagePreset(imageKey = imageKey, type = type, deactivatedAt = deactivatedAt))
}
