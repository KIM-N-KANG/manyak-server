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

    @Test
    fun `축소 변형은 썸네일 경로에 _sm 접미사를 붙인다`() {
        assertThat(resolver.thumbnailSmUrlFor("thumb_0012"))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0012_sm.png")
    }

    @Test
    fun `축소 변형도 imageKey나 base URL이 없으면 null이다`() {
        assertThat(resolver.thumbnailSmUrlFor(null)).isNull()
        assertThat(ImageUrlResolver("").thumbnailSmUrlFor("thumb_0001")).isNull()
    }

    /** 원본 키는 불변이고 `_sm`은 URL 조합 시에만 파생된다(DB에 저장하지 않음). */
    @Test
    fun `같은 imageKey에서 원본과 축소 URL이 함께 조합된다`() {
        assertThat(resolver.urlFor("thumb_0001", ImagePresetType.THUMBNAIL))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0001.png")
        assertThat(resolver.thumbnailSmUrlFor("thumb_0001"))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0001_sm.png")
    }

    /** KNK-1069: 컴파일이 생성한 표지가 있으면 프리셋 키보다 우선한다(2단 폴백은 리졸버가 소유). */
    @Test
    fun `생성 표지 URL이 있으면 프리셋 키보다 우선한다`() {
        assertThat(resolver.thumbnailUrlFor("https://cdn.test/thumbnails/generated/s/t_1a2b3c4d.webp", "thumb_0012"))
            .isEqualTo("https://cdn.test/thumbnails/generated/s/t_1a2b3c4d.webp")
    }

    @Test
    fun `생성 표지 URL이 없거나 공백이면 프리셋 키로 떨어진다`() {
        assertThat(resolver.thumbnailUrlFor(null, "thumb_0012"))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0012.png")
        assertThat(resolver.thumbnailUrlFor("   ", "thumb_0012"))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0012.png")
    }

    @Test
    fun `생성 표지도 프리셋 키도 없으면 null이다`() {
        assertThat(resolver.thumbnailUrlFor(null, null)).isNull()
    }

    /** 축소 변형 자리에도 생성 표지는 **원본 URL 그대로** 쓴다(생성 표지는 `_sm` 파생본을 만들지 않는다). */
    @Test
    fun `축소 변형 자리에는 생성 표지 원본 URL을 그대로 쓴다`() {
        assertThat(resolver.thumbnailSmUrlFor("https://cdn.test/thumbnails/generated/s/t_1a2b3c4d.webp", "thumb_0012"))
            .isEqualTo("https://cdn.test/thumbnails/generated/s/t_1a2b3c4d.webp")
    }

    @Test
    fun `축소 변형도 생성 표지가 없으면 프리셋 _sm으로 떨어진다`() {
        assertThat(resolver.thumbnailSmUrlFor(null, "thumb_0012"))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0012_sm.png")
        assertThat(resolver.thumbnailSmUrlFor("  ", "thumb_0012"))
            .isEqualTo("https://cdn.manyak.app/thumbnails/thumb_0012_sm.png")
        assertThat(resolver.thumbnailSmUrlFor(null, null)).isNull()
    }
}
