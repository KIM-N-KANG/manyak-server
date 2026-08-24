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
    fun `예산이 넉넉하면 새 호출을 시작한다`() {
        assertThat(ImageStageBudget.startingNow(Duration.ofSeconds(30)).hasRoomForCall()).isTrue()
    }

    @Test
    fun `마감이 지났으면 새 호출을 시작하지 않는다`() {
        assertThat(ImageStageBudget(System.nanoTime() - 1).hasRoomForCall()).isFalse()
    }

    @Test
    fun `예산이 0이면 시작하자마자 소진된 상태다`() {
        assertThat(ImageStageBudget.startingNow(Duration.ZERO).hasRoomForCall()).isFalse()
    }

    @Test
    fun `남은 시간이 호출 1회 상한보다 적으면 시작하지 않는다`() {
        // 마감까지 상한의 절반만 남은 상태. 여기서 호출을 시작하면 그 호출이 마감을 넘겨 끝날 수 있다.
        val halfCall = ImageStageBudget.CALL_TIMEOUT.dividedBy(2)

        assertThat(ImageStageBudget.startingNow(halfCall).hasRoomForCall()).isFalse()
    }

    @Test
    fun `남은 시간이 호출 1회 상한보다 넉넉하면 시작한다`() {
        val twoCalls = ImageStageBudget.CALL_TIMEOUT.multipliedBy(2)

        assertThat(ImageStageBudget.startingNow(twoCalls).hasRoomForCall()).isTrue()
    }
}
