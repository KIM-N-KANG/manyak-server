package com.knk.manyak.user.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

/** 닉네임 정규화 키(KNK-1147). 유일 판정과 마이그레이션 인덱스가 **같은 식**을 써야 한다. */
class NicknamePolicyTest {

    @Test
    fun `정규화 키는 소문자로 낮추고 공백을 전부 지운다`() {
        assertThat(nicknameKeyOf("Story Teller")).isEqualTo("storyteller")
        assertThat(nicknameKeyOf("STORYTELLER")).isEqualTo("storyteller")
        assertThat(nicknameKeyOf("몽환적인 이야기꾼")).isEqualTo("몽환적인이야기꾼")
    }
    @ParameterizedTest
    @ValueSource(strings = ["닉 네임", " 닉네임", "닉네임 ", "닉  네임", "닉네임#1234", "닉\t네임", "닉네임\n"])
    fun `공백이나 샵이 들어간 닉네임은 거부한다`(nickname: String) {
        assertThatThrownBy { requireValidNickname(nickname) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["달빛작가4817", "Writer1234", "한글ABC123", "가나", "1234"])
    fun `한글 영문 숫자는 그대로 통과한다`(nickname: String) {
        assertThat(requireValidNickname(nickname)).isEqualTo(nickname)
    }
}
