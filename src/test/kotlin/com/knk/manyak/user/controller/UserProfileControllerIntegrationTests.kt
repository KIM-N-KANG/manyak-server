package com.knk.manyak.user.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.ProfileImagePresetService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 프로필 수정 API 통합 검증(KNK-1147, 정책 KNK-1146).
 * - 닉네임은 trim 후 2~20자, 한글 완성형·영문·숫자·공백만 허용하고 연속 공백은 막는다.
 * - 유일성은 정규화 키(소문자 + 공백 제거) 기준이라, 대소문자·공백만 다른 닉네임은 409다.
 * - 프로필 이미지는 프리셋 선택만 지원한다(업로드 없음). 닉네임 변경과 독립이다.
 * - 인증 필수이며 정지 계정은 403이다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserProfileControllerIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var profileImagePresetService: ProfileImagePresetService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(
        nickname: String = "몽환적인 이야기꾼",
        status: UserStatus = UserStatus.ACTIVE,
    ): User = userRepository.save(
        User(
            nickname = nickname,
            status = status,
            profileImageUrl = "https://api.manyak.app/profile-presets/%EC%9D%B4%EC%95%BC%EA%B8%B0%EA%BE%BC.png",
            profileThumbnailBase64 = "seed-thumb",
        ),
    )

    private fun bearer(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    private fun patch(user: User, body: String) =
        restTestClient.patch()
            .uri(PATH)
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()

    private fun reload(user: User): User = userRepository.findById(user.id).orElseThrow()

    @Test
    fun `토큰 없이 수정하거나 프리셋을 조회하면 401이다`() {
        restTestClient.patch()
            .uri(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"nickname":"새 닉네임"}""")
            .exchange()
            .expectStatus().isUnauthorized

        restTestClient.get().uri(PRESETS_PATH).exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `닉네임만 바꾸면 프로필 이미지는 그대로다`() {
        val user = saveUser()

        patch(user, """{"nickname":"새로운 작가"}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nickname").isEqualTo("새로운 작가")
            .jsonPath("$.profileImageUrl").isNotEmpty

        val reloaded = reload(user)
        assertThat(reloaded.nickname).isEqualTo("새로운 작가")
        assertThat(reloaded.profileThumbnailBase64).isEqualTo("seed-thumb")
    }

    @Test
    fun `프리셋만 바꾸면 닉네임은 그대로다`() {
        val user = saveUser()

        patch(user, """{"profileImagePreset":"마법사"}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nickname").isEqualTo("몽환적인 이야기꾼")

        val reloaded = reload(user)
        assertThat(reloaded.nickname).isEqualTo("몽환적인 이야기꾼")
        assertThat(reloaded.profileImageUrl).isEqualTo(profileImagePresetService.imageUrlFor("마법사"))
        assertThat(reloaded.profileThumbnailBase64).isEqualTo(profileImagePresetService.thumbnailBase64For("마법사"))
    }

    @Test
    fun `닉네임과 프리셋을 함께 바꾸면 둘 다 반영된다`() {
        val user = saveUser()

        patch(user, """{"nickname":"이야기 수집가","profileImagePreset":"수집가"}""").expectStatus().isOk

        val reloaded = reload(user)
        assertThat(reloaded.nickname).isEqualTo("이야기 수집가")
        assertThat(reloaded.profileImageUrl).isEqualTo(profileImagePresetService.imageUrlFor("수집가"))
    }

    @Test
    fun `빈 본문은 400이다`() {
        patch(saveUser(), "{}").expectStatus().isBadRequest
    }

    @Test
    fun `닉네임에 null만 보내면 400이다`() {
        // 닉네임은 지울 수 없는 값이라 null 하나만 보내는 요청은 바꿀 것이 없는 요청과 같다.
        patch(saveUser(), """{"nickname":null}""").expectStatus().isBadRequest
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "가",                    // 1자
            "가나다라마바사아자차카타파하가나다라마바사",  // 21자
            "닉네임!",                // 특수문자
            "닉  네임",               // 연속 공백
            "ㄱㄴㄷ",                 // 자모 단독
            "닉네임😀",               // 이모지
            "   ",                   // 공백뿐(trim 후 빈 문자열)
        ],
    )
    fun `허용 규칙을 어기는 닉네임은 400이다`(nickname: String) {
        val user = saveUser()

        patch(user, """{"nickname":"$nickname"}""").expectStatus().isBadRequest

        assertThat(reload(user).nickname).isEqualTo("몽환적인 이야기꾼")
    }

    @Test
    fun `앞뒤 공백은 지우고 저장한다`() {
        val user = saveUser()

        patch(user, """{"nickname":"  다듬은 닉네임  "}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nickname").isEqualTo("다듬은 닉네임")

        assertThat(reload(user).nickname).isEqualTo("다듬은 닉네임")
    }

    @Test
    fun `다른 회원과 대소문자나 공백만 다른 닉네임은 409다`() {
        saveUser(nickname = "Story Teller")
        val user = saveUser(nickname = "몽환적인 이야기꾼")

        patch(user, """{"nickname":"storyteller"}""")
            .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NICKNAME_TAKEN")

        assertThat(reload(user).nickname).isEqualTo("몽환적인 이야기꾼")
    }

    @Test
    fun `자기 닉네임의 대소문자만 바꾸는 것은 허용한다`() {
        val user = saveUser(nickname = "Story Teller")

        patch(user, """{"nickname":"STORY TELLER"}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nickname").isEqualTo("STORY TELLER")
    }

    @Test
    fun `없는 프리셋 키는 400이다`() {
        val user = saveUser()

        patch(user, """{"profileImagePreset":"없는프리셋"}""").expectStatus().isBadRequest

        assertThat(reload(user).profileThumbnailBase64).isEqualTo("seed-thumb")
    }

    @Test
    fun `정지된 계정은 403이다`() {
        val suspended = saveUser(status = UserStatus.SUSPENDED)

        patch(suspended, """{"nickname":"바꾸고 싶다"}""").expectStatus().isForbidden

        assertThat(reload(suspended).nickname).isEqualTo("몽환적인 이야기꾼")
    }

    @Test
    fun `프리셋 목록은 키와 이미지 URL을 함께 돌려준다`() {
        val user = saveUser()

        restTestClient.get()
            .uri(PRESETS_PATH)
            .header("Authorization", bearer(user))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(profileImagePresetService.presetKeys().size)
            .jsonPath("$[0].key").isNotEmpty
            .jsonPath("$[0].imageUrl").isNotEmpty
    }

    private companion object {
        const val PATH = "/api/v1/users/me"
        const val PRESETS_PATH = "/api/v1/profile-presets"
    }
}
