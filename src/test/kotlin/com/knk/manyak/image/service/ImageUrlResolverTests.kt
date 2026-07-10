package com.knk.manyak.image.service

import com.knk.manyak.image.entity.ImagePresetType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `imageKey` → 서빙 URL 조합 규칙을 검증한다(스펙 §4-3-9: `{base}/{prefix}/{imageKey}.png`). */
class ImageUrlResolverTests {

    private val resolver = ImageUrlResolver("https://cdn.manyak.app")

    @Test
    fun `타입별 prefix와 png 확장자로 URL을 조합한다`() {
        assertThat(resolver.urlFor("thumb_0012", ImagePresetType.THUMBNAIL))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0012.png")
        assertThat(resolver.urlFor("bg_0007", ImagePresetType.BACKGROUND))
            .isEqualTo("https://cdn.manyak.app/backgrounds/bg_0007.png")
        assertThat(resolver.urlFor("char_0031", ImagePresetType.CHARACTER))
            .isEqualTo("https://cdn.manyak.app/characters/char_0031.png")
    }

    @Test
    fun `base URL 끝의 슬래시는 중복되지 않는다`() {
        val trailing = ImageUrlResolver("https://cdn.manyak.app/")

        assertThat(trailing.urlFor("thumb_0001", ImagePresetType.THUMBNAIL))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0001.png")
    }

    @Test
    fun `imageKey가 없으면 null이다`() {
        assertThat(resolver.urlFor(null, ImagePresetType.THUMBNAIL)).isNull()
    }

    @Test
    fun `base URL이 비어 있으면 null이다`() {
        val unconfigured = ImageUrlResolver("")

        assertThat(unconfigured.urlFor("thumb_0001", ImagePresetType.THUMBNAIL)).isNull()
    }
}
