package com.knk.manyak.auth.social

import org.springframework.stereotype.Component

/**
 * 발급된 닉네임. [text]는 표시 닉네임(형용사+명사)이고, [noun]은 프로필 프리셋 이미지 매핑에 쓰는 명사다
 * (스펙 §4-5 B7 — 명사에 1:1 매핑된 프리셋 배정). 조합에서 명사를 문자열로 재파싱하지 않도록 함께 반환한다.
 */
data class GeneratedNickname(val text: String, val noun: String)

/**
 * 회원가입 시 부여할 표시 닉네임을 만든다.
 *
 * 랜덤 발급을 구현체 뒤로 감춰, 사용하는 쪽([GoogleAccountRegistrar])은 값의 출처(랜덤·유도 등)에 의존하지 않는다.
 */
fun interface NicknameGenerator {
    fun generate(): GeneratedNickname
}

/**
 * 한국어 형용사 + 명사 조합으로 닉네임을 랜덤 생성한다(예: "몽환적인 이야기꾼").
 *
 * 백엔드 스펙 §4-5 "가입 프로필 발급": 실명(Google `name`) 노출을 피하기 위한 랜덤 발급. 50자 이내, 중복 허용(식별은 `public_id`).
 * 단어 풀은 마냑("내가 쓰고 AI가 이어가는 나만의 이야기")의 세계관에 맞춰 이야기 속 인물·창작자 정체성과
 * 서사적 무드로 구성한다. 각 토큰은 공백이 없어 "형용사 공백 명사" 형식이 항상 성립한다.
 */
@Component
class RandomNicknameGenerator : NicknameGenerator {

    override fun generate(): GeneratedNickname {
        val noun = NOUNS.random()
        // 명사는 프리셋 이미지 매핑 키다. text는 컬럼 길이 방어로 절단하되(실제 조합은 훨씬 짧음), noun은 매핑용 원본을 그대로 반환한다.
        return GeneratedNickname(text = "${ADJECTIVES.random()} $noun".take(MAX_NICKNAME_LENGTH), noun = noun)
    }

    companion object {
        /** `users.nickname`은 VARCHAR(50). 조합이 이보다 길어질 일은 없지만 컬럼 길이를 넘지 않도록 방어한다. */
        const val MAX_NICKNAME_LENGTH = 50

        // 서사적 무드의 관형형. 각 항목은 공백 없는 단일 토큰이어야 한다(형식 계약).
        val ADJECTIVES = listOf(
            "용맹한", "몽환적인", "전설적인", "신비로운", "떠도는", "운명적인", "자유로운", "서사적인",
            "상상하는", "꿈꾸는", "고독한", "낭만적인", "비밀스러운", "매혹적인", "지혜로운", "빛나는",
            "잠들지않는", "끝없는", "별빛의", "황혼의", "새벽의", "폭풍의", "심연의", "헤매는",
            "다정한", "대담한", "그림자의", "환상의", "영원한", "고요한", "찬란한", "머나먼",
            "잊혀진", "예언된", "기묘한", "불멸의", "태초의", "수수께끼의", "유쾌한", "담대한",
        )

        // 이야기 세계 속 인물과 창작자 정체성. 각 항목은 공백 없는 단일 토큰이어야 한다(형식 계약).
        val NOUNS = listOf(
            "이야기꾼", "몽상가", "방랑자", "나그네", "음유시인", "여행자", "탐험가", "모험가",
            "주인공", "화자", "작가", "서기", "사서", "현자", "예언자", "연금술사",
            "마법사", "기사", "검객", "궁수", "수호자", "관찰자", "길잡이", "개척자",
            "이방인", "창조자", "수집가", "파수꾼", "순례자", "표류자", "낭독가", "유랑자",
            "은둔자", "광대", "마술사", "예술가", "시인", "무희", "악사", "요정",
        )
    }
}
