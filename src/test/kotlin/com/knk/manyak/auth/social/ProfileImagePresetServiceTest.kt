package com.knk.manyak.auth.social

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 프로필 프리셋 이미지 배정 계약을 고정한다(스펙 §4-5 B7, KNK-388).
 *
 * static 리소스(`static/profile-presets/{명사}.png`)는 테스트 클래스패스에 있으므로 서비스가 시작 시 실제로 로드한다.
 * - 명사 풀 전체(40종)가 프리셋을 가진다(1:1 매핑).
 * - URL은 base + "/profile-presets/" + 퍼센트 인코딩된 명사 + ".png"이고, 썸네일은 디코딩 가능한 PNG base64다.
 * - 매핑 없는 명사는 URL·썸네일 모두 null(기본 아바타 폴백).
 */
class ProfileImagePresetServiceTest {

    private val service = ProfileImagePresetService(BASE_URL)

    @Test
    fun `명사에 매핑된 프리셋 URL은 base와 퍼센트 인코딩된 경로로 만든다`() {
        val encoded = UriUtils.encodePathSegment("이야기꾼", StandardCharsets.UTF_8)

        assertThat(service.imageUrlFor("이야기꾼"))
            .isEqualTo("$BASE_URL/profile-presets/$encoded.png")
    }

    @Test
    fun `썸네일은 디코딩 가능한 PNG base64다`() {
        val thumbnail = service.thumbnailBase64For("이야기꾼")

        assertThat(thumbnail).isNotNull()
        val bytes = Base64.getDecoder().decode(thumbnail)
        // PNG 시그니처(0x89 'P' 'N' 'G').
        assertThat(bytes.size).isGreaterThan(8)
        assertThat(bytes[0].toInt() and 0xFF).isEqualTo(0x89)
        assertThat(bytes[1].toInt()).isEqualTo('P'.code)
        assertThat(bytes[2].toInt()).isEqualTo('N'.code)
        assertThat(bytes[3].toInt()).isEqualTo('G'.code)
    }

    @Test
    fun `매핑 없는 명사는 URL과 썸네일이 모두 null이다`() {
        assertThat(service.imageUrlFor("존재하지않는명사")).isNull()
        assertThat(service.thumbnailBase64For("존재하지않는명사")).isNull()
    }

    @Test
    fun `base URL의 후행 슬래시는 중복 없이 정규화된다`() {
        val withSlash = ProfileImagePresetService("https://cdn.example.com/")

        assertThat(withSlash.imageUrlFor("이야기꾼")).startsWith("https://cdn.example.com/profile-presets/")
    }

    @Test
    fun `닉네임 명사 풀 전체가 프리셋 이미지를 가진다`() {
        // 스펙 §4-5: 명사 40종에 이미지 40종을 1:1 매핑. 누락이 있으면 해당 명사 회원은 기본 아바타로 폴백된다.
        assertThat(RandomNicknameGenerator.NOUNS)
            .allMatch { service.imageUrlFor(it) != null && service.thumbnailBase64For(it) != null }
    }

    private companion object {
        const val BASE_URL = "https://api.manyak.app"
    }
}
