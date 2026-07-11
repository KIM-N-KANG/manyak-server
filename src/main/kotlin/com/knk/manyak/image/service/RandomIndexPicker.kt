package com.knk.manyak.image.service

import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

/** 랜덤 선택의 이음매. 테스트가 결정적으로 고를 수 있도록 주입 가능한 형태로 둔다. */
fun interface RandomIndexPicker {
    /** `[0, bound)` 범위의 인덱스를 하나 고른다. */
    fun pick(bound: Int): Int
}

@Component
class ThreadLocalRandomIndexPicker : RandomIndexPicker {
    override fun pick(bound: Int): Int = ThreadLocalRandom.current().nextInt(bound)
}
