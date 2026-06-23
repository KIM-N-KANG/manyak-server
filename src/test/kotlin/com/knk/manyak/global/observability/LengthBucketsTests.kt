package com.knk.manyak.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LengthBucketsTests {

    @Test
    fun `경계값을 올바른 구간 라벨로 변환한다`() {
        assertThat(LengthBuckets.of(0)).isEqualTo("0")
        assertThat(LengthBuckets.of(1)).isEqualTo("1-20")
        assertThat(LengthBuckets.of(20)).isEqualTo("1-20")
        assertThat(LengthBuckets.of(21)).isEqualTo("21-100")
        assertThat(LengthBuckets.of(100)).isEqualTo("21-100")
        assertThat(LengthBuckets.of(101)).isEqualTo("101-300")
        assertThat(LengthBuckets.of(300)).isEqualTo("101-300")
        assertThat(LengthBuckets.of(301)).isEqualTo("301-1000")
        assertThat(LengthBuckets.of(1000)).isEqualTo("301-1000")
        assertThat(LengthBuckets.of(1001)).isEqualTo("1001+")
    }

    @Test
    fun `음수는 0 구간으로 처리한다`() {
        assertThat(LengthBuckets.of(-5)).isEqualTo("0")
    }
}
