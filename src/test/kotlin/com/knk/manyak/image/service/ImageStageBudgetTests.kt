package com.knk.manyak.image.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 이미지 단계 시간 예산 게이트(KNK-966). 통합 테스트로는 예산 소진을 결정적으로 만들 수 없어(주입 가능한 시계가 없다)
 * 게이트 자체를 단위로 고정한다. 호출부는 각 S3 호출 전에 [ImageStageBudget.hasRemaining]을 본다.
 */
class ImageStageBudgetTests {

    @Test
    fun `예산이 남아 있으면 계속 진행한다`() {
        assertThat(ImageStageBudget.startingNow(Duration.ofSeconds(30)).hasRemaining()).isTrue()
    }

    @Test
    fun `마감이 지났으면 남은 예산이 없다`() {
        assertThat(ImageStageBudget(System.nanoTime() - 1).hasRemaining()).isFalse()
    }

    @Test
    fun `예산이 0이면 시작하자마자 소진된 상태다`() {
        assertThat(ImageStageBudget.startingNow(Duration.ZERO).hasRemaining()).isFalse()
    }
}
