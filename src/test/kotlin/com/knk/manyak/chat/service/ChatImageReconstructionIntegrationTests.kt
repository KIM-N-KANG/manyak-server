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
import java.time.temporal.ChronoUnit

/**
 * 지난 턴 `images[]` 재구성의 불변을 검증한다(스펙 §4-3-9).
 *
 * `images[]`는 저장하지 않으므로 상세 조회는 본문 마커에서 다시 만든다. 그 결과가 `completed`가 내려준 것과
 * 같으려면 **그 턴의 확정 시각 시점 카탈로그 상태**를 봐야 한다:
 *   등록 시각 <= 확정 시각 AND (deactivated_at IS NULL OR deactivated_at > 확정 시각)
 *
 * 앞 조건이 없으면 저장 시점에 무효였던 마커(오타·후보 밖 키)가 이후 시드로 소급 유효해지고,
 * 뒤 조건이 없으면 확정 시점에 비활성이던 키가 재구성에서 되살아난다.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestPropertySource(properties = ["manyak.asset.image-base-url=https://cdn.test"])
class ChatImageReconstructionIntegrationTests {

    @Autowired private lateinit var bundler: ChatImageBundler
    @Autowired private lateinit var imagePresetRepository: ImagePresetRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private val confirmedAt: Instant = Instant.parse("2026-07-10T12:00:00Z")
    private val before: Instant = confirmedAt.minus(1, ChronoUnit.HOURS)
    private val after: Instant = confirmedAt.plus(1, ChronoUnit.HOURS)

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `확정 시각 이전에 등재되고 비활성이 아니면 재구성에 실린다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND, createdAt = before)

        val images = reconstructOne("[[image:bg_0007]]")

        assertThat(images).singleElement().satisfies({
            assertThat(it.imageKey).isEqualTo("bg_0007")
            assertThat(it.url).isEqualTo("https://cdn.test/backgrounds/bg_0007.png")
        })
    }

    /** 등록 시각 컷오프 — 저장 시점에 없던 키가 이후 시드로 소급 유효해지면 안 된다. */
    @Test
    fun `확정 시각 이후에 등재된 키는 재구성에서도 무효다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND, createdAt = after)

        assertThat(reconstructOne("[[image:bg_0007]]")).isEmpty()
    }

    /** 비활성 시각 컷오프 ① — 비활성 이전에 확정된 턴은 지난 기록으로 계속 보인다. */
    @Test
    fun `비활성 이전에 확정된 턴에는 계속 실린다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND, createdAt = before, deactivatedAt = after)

        assertThat(reconstructOne("[[image:bg_0007]]").map { it.imageKey }).containsExactly("bg_0007")
    }

    /** 비활성 시각 컷오프 ② — 비활성 중에 확정된 턴의 마커는 completed에서도 걸렸으므로 재구성에서도 무효다. */
    @Test
    fun `비활성 이후에 확정된 턴에서는 무효다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND, createdAt = before, deactivatedAt = before)

        assertThat(reconstructOne("[[image:bg_0007]]")).isEmpty()
    }

    @Test
    fun `카탈로그에 없는 키는 재구성에서도 무효다`() {
        assertThat(reconstructOne("[[image:bg_9999]]")).isEmpty()
    }

    @Test
    fun `타입별 턴당 1장 상한은 재구성에도 적용된다`() {
        savePreset("bg_0001", ImagePresetType.BACKGROUND, createdAt = before)
        savePreset("bg_0002", ImagePresetType.BACKGROUND, createdAt = before)
        savePreset("char_0001", ImagePresetType.CHARACTER, createdAt = before)

        val images = reconstructOne("[[image:bg_0001]][[image:bg_0002]][[image:char_0001]]")

        assertThat(images.map { it.imageKey }).containsExactly("bg_0001", "char_0001")
    }

    /** 턴마다 확정 시각이 다르므로 컷오프도 턴별로 갈린다(조회는 한 번). */
    @Test
    fun `턴마다 확정 시각 기준으로 따로 판정한다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND, createdAt = confirmedAt)

        val result = bundler.reconstruct(
            listOf(
                ConfirmedTurnContent(turnId = 1, aiOutput = "[[image:bg_0007]]", contentConfirmedAt = before),
                ConfirmedTurnContent(turnId = 2, aiOutput = "[[image:bg_0007]]", contentConfirmedAt = after),
            ),
        )

        assertThat(result.getValue(1)).isEmpty() // 등재 전에 확정된 턴
        assertThat(result.getValue(2).map { it.imageKey }).containsExactly("bg_0007")
    }

    /** completed와 상세 조회가 같은 결과를 내야 한다 — 재구성 불변의 본질. */
    @Test
    fun `활성 카탈로그에서는 completed 결과와 재구성 결과가 같다`() {
        savePreset("bg_0007", ImagePresetType.BACKGROUND, createdAt = before)
        savePreset("char_0031", ImagePresetType.CHARACTER, createdAt = before)
        val aiOutput = "노을.[[image:bg_0007]] 그녀.[[image:char_0031]]"

        val completed = bundler.bundle(aiOutput)
        val reconstructed = reconstructOne(aiOutput)

        assertThat(reconstructed).isEqualTo(completed)
    }

    private fun reconstructOne(aiOutput: String) =
        bundler.reconstruct(
            listOf(ConfirmedTurnContent(turnId = 1, aiOutput = aiOutput, contentConfirmedAt = confirmedAt)),
        ).getValue(1)

    private fun savePreset(
        imageKey: String,
        type: ImagePresetType,
        createdAt: Instant,
        deactivatedAt: Instant? = null,
    ): ImagePreset =
        imagePresetRepository.save(
            ImagePreset(imageKey = imageKey, type = type, createdAt = createdAt, deactivatedAt = deactivatedAt),
        )
}
