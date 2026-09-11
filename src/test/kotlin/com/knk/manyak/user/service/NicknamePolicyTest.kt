package com.knk.manyak.user.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 닉네임 정규화 키(KNK-1147). 유일 판정과 마이그레이션 인덱스가 **같은 식**을 써야 한다. */
class NicknamePolicyTest {

    @Test
    fun `정규화 키는 소문자로 낮추고 공백을 전부 지운다`() {
        assertThat(nicknameKeyOf("Story Teller")).isEqualTo("storyteller")
        assertThat(nicknameKeyOf("STORYTELLER")).isEqualTo("storyteller")
        assertThat(nicknameKeyOf("몽환적인 이야기꾼")).isEqualTo("몽환적인이야기꾼")
    }
}
