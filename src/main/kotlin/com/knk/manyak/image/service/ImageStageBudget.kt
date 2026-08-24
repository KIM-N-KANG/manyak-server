package com.knk.manyak.image.service

import java.time.Duration

/**
 * 인물 이미지 단계(업로드 · 업로드 실패 즉시 삭제 · 트랜잭션 후 보상 삭제) 전체에 걸리는 시간 예산(KNK-966).
 *
 * S3 호출 하나에는 SDK 상한(`apiCallTimeout`)이 걸려 있지만, 호출 여러 개가 순차로 누적되는 것까지는 막지 못한다.
 * 예산이 소진되면 남은 S3 호출을 건너뛰어, 저장소가 느릴 때 이미지 단계가 스토리 생성 요청을 끌고 가지 않게 한다.
 *
 * 시계는 [System.nanoTime]이다 — 벽시계 보정에 흔들리지 않는 단조 증가 시계라 경과 시간 측정에 맞는다.
 */
class ImageStageBudget(private val deadlineNanos: Long) {

    fun hasRemaining(): Boolean = System.nanoTime() < deadlineNanos

    companion object {
        fun startingNow(budget: Duration): ImageStageBudget =
            ImageStageBudget(System.nanoTime() + budget.toNanos())
    }
}
