package com.knk.manyak.story.service

import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

/** 커서 없는 탐색 요청에 새 64비트 시드를 공급한다. */
@Component
class StoryExploreSeedSource {
    fun nextSeed(): Long = ThreadLocalRandom.current().nextLong()
}
