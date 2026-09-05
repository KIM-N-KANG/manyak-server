package com.knk.manyak.auth.social

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.user.service.nicknameKeyOf
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * 가입 시 쓸 랜덤 닉네임을 **정규화 기준 유일하게** 발급한다(KNK-1147).
 *
 * 닉네임이 유일해지면서(V75) 발급도 충돌할 수 있다. 조합이 형용사 40 × 명사 40 = 1,600가지뿐이라 회원이
 * 늘면 실제로 부딪힌다. 다시 뽑아 보고([MAX_ATTEMPTS]회), 그래도 안 되면 접미를 붙여 **가입 자체는
 * 실패시키지 않는다** — 여기서 예외를 던지면 조합 고갈이 곧 회원가입 장애가 된다.
 *
 * 사전 조회와 저장 사이의 경합은 유니크 인덱스가 막고, 그 위반은 로그인 경로가 재시도로 흡수한다
 * ([com.knk.manyak.auth.social.SocialLoginService] find-or-create).
 *
 * 명사([GeneratedNickname.noun])는 접미와 무관하게 원본을 유지한다 — 프리셋 이미지 매핑 키이기 때문이다(KNK-388).
 */
@Component
class UniqueNicknameIssuer(
    private val nicknameGenerator: NicknameGenerator,
    private val userRepository: UserRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun issue(): GeneratedNickname {
        var candidate = nicknameGenerator.generate()
        repeat(MAX_ATTEMPTS) {
            if (!userRepository.existsByNicknameKey(nicknameKeyOf(candidate.text))) {
                return candidate
            }
            candidate = nicknameGenerator.generate()
        }
        // 마지막 후보에 짧은 난수를 붙여 확률적으로 유일하게 만든다. 컬럼 길이(50)를 넘지 않도록 앞을 자른다.
        val suffix = "#%04d".format(Random.nextInt(SUFFIX_BOUND))
        val text = candidate.text.take(RandomNicknameGenerator.MAX_NICKNAME_LENGTH - suffix.length) + suffix
        logger.info("랜덤 닉네임이 계속 충돌해 접미를 붙여 발급합니다. (attempts={})", MAX_ATTEMPTS)
        return candidate.copy(text = text)
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val SUFFIX_BOUND = 10_000
    }
}
