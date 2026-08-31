package com.knk.manyak.image.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 생성 이미지 객체 키 생성(KNK-1010, KNK-1069 썸네일 확장). 파일명에 인물 이름을 드러내되, LLM이 만든 이름이므로 키에 쓸 수 있는
 * 문자만 남긴다. 이름은 신뢰 경계라 여기서 걸러진 결과가 곧 S3 키가 된다.
 */
class GeneratedImageObjectKeysTests {

    private val storyPublicId = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")

    @Test
    fun `한글 이름은 그대로 살린다`() {
        assertThat(GeneratedImageObjectKeys.sanitize("카일")).isEqualTo("카일")
        assertThat(GeneratedImageObjectKeys.sanitize("카일_기쁨")).isEqualTo("카일_기쁨")
    }

    @Test
    fun `영숫자와 언더스코어는 그대로 살린다`() {
        assertThat(GeneratedImageObjectKeys.sanitize("Kyle_2")).isEqualTo("Kyle_2")
    }

    @Test
    fun `그 밖의 문자는 언더스코어로 바꾼다`() {
        // 키·URL을 깨뜨리는 문자(공백·슬래시·#·?·%)가 특히 중요하다.
        assertThat(GeneratedImageObjectKeys.sanitize("카일 기쁨")).isEqualTo("카일_기쁨")
        assertThat(GeneratedImageObjectKeys.sanitize("a/b#c?d%e")).isEqualTo("a_b_c_d_e")
        assertThat(GeneratedImageObjectKeys.sanitize("카일(기쁨)")).isEqualTo("카일_기쁨_")
    }

    @Test
    fun `살릴 문자가 없으면 폴백 이름을 쓴다`() {
        // 빈 파일명(`_abc12345.webp`)이 되면 누구 것인지도 모르고 키만 이상해진다.
        assertThat(GeneratedImageObjectKeys.sanitize("")).isEqualTo(GeneratedImageObjectKeys.FALLBACK_NAME_CHARACTER)
        assertThat(GeneratedImageObjectKeys.sanitize("   ")).isEqualTo(GeneratedImageObjectKeys.FALLBACK_NAME_CHARACTER)
        assertThat(GeneratedImageObjectKeys.sanitize("!!!")).isEqualTo(GeneratedImageObjectKeys.FALLBACK_NAME_CHARACTER)
    }

    @Test
    fun `객체 키는 인물 이름과 uuid 앞 8자리로 만든다`() {
        val key = GeneratedImageObjectKeys.newObjectKey(GeneratedImageObjectKeys.KEY_PREFIX_CHARACTER, storyPublicId, "카일 기쁨")

        assertThat(key).matches("characters/generated/$storyPublicId/카일_기쁨_[0-9a-f]{8}\\.webp")
    }

    @Test
    fun `같은 인물이라도 호출마다 다른 키를 만든다`() {
        // uuid 접미는 재생성 시 새 키를 발급해 CDN 장기 캐시가 옛 이미지를 물고 있지 않게 한다(키 불변성).
        val first = GeneratedImageObjectKeys.newObjectKey(GeneratedImageObjectKeys.KEY_PREFIX_CHARACTER, storyPublicId, "카일")
        val second = GeneratedImageObjectKeys.newObjectKey(GeneratedImageObjectKeys.KEY_PREFIX_CHARACTER, storyPublicId, "카일")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `썸네일 키는 thumbnails generated prefix를 쓰고 형식은 인물과 같다`() {
        val key = GeneratedImageObjectKeys.newObjectKey(
            GeneratedImageObjectKeys.KEY_PREFIX_THUMBNAIL,
            storyPublicId,
            "썸네일 기본",
            GeneratedImageObjectKeys.FALLBACK_NAME_THUMBNAIL,
        )

        assertThat(key).matches("thumbnails/generated/$storyPublicId/썸네일_기본_[0-9a-f]{8}\\.webp")
    }

    @Test
    fun `썸네일은 이름이 비면 thumbnail로 폴백한다`() {
        assertThat(GeneratedImageObjectKeys.sanitize("", GeneratedImageObjectKeys.FALLBACK_NAME_THUMBNAIL))
            .isEqualTo("thumbnail")
    }
}
