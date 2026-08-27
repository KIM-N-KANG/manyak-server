package com.knk.manyak.image.service

import java.util.UUID

/**
 * 인물 이미지 객체 키 생성(KNK-966 도입, KNK-1010 가독화).
 *
 * 키는 `characters/generated/{storyPublicId}/{인물 이름}_{uuid 앞 8자리}.webp`다.
 *
 * **파일명에 이름을 넣는 이유**: 이전 형식(`{uuid}.webp`)은 버킷에서 누구 이미지인지 알 수 없었다.
 * 이름은 이후 `카일_기쁨`처럼 표정·상태를 담는 설명자로 확장될 계획이라 파일명으로서 값이 더 커진다.
 *
 * **uuid 접미를 남기는 이유**: 같은 인물을 재생성하면 새 키를 발급해야 CDN 장기 캐시가 옛 이미지를 물고
 * 있지 않다(키 불변 — 내용이 바뀌면 키도 바뀐다). 이름만 쓰면 같은 키에 덮어써 스테일이 남는다.
 *
 * **이름은 신뢰 경계다.** LLM이 만든 값이라 공백·슬래시·`#`·`?`·`%` 같은 문자가 그대로 들어오면 키가 깨지거나
 * URL 경로가 잘린다. 그래서 한글·영숫자·언더스코어만 남기고 나머지는 언더스코어로 바꾼다.
 */
object CharacterImageObjectKeys {

    const val KEY_PREFIX = "characters/generated"
    const val EXTENSION = "webp"

    /** 남길 문자가 하나도 없는 이름(빈 문자열·기호뿐)일 때 쓰는 파일명. */
    const val FALLBACK_NAME = "character"

    // 완성형 한글·자모, 영숫자, 언더스코어만 그대로 둔다. 그 외는 전부 치환 대상이다.
    private val KEEP = Regex("[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9_]")

    // 키 충돌 회피용 접미. 8자리면 한 스토리 안에서 실질 충돌이 없고 파일명도 짧게 유지된다.
    private const val SUFFIX_LENGTH = 8

    /** 인물 이름을 객체 키에 쓸 수 있는 파일명으로 바꾼다. 남는 게 구분자뿐이면 [FALLBACK_NAME]. */
    fun sanitize(name: String): String {
        val sanitized = name.map { char -> if (KEEP.matches(char.toString())) char else '_' }.joinToString("")
        // 빈 이름이나 기호뿐인 이름은 밑줄만 남아(`___`) 파일명 구실을 못 하므로 폴백으로 바꾼다.
        return if (sanitized.isEmpty() || sanitized.all { it == '_' }) FALLBACK_NAME else sanitized
    }

    /** 이 업로드에 쓸 객체 키를 만든다. 호출마다 접미가 달라 같은 인물도 새 키를 받는다. */
    fun newObjectKey(storyPublicId: UUID, name: String): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(SUFFIX_LENGTH)
        return "$KEY_PREFIX/$storyPublicId/${sanitize(name)}_$suffix.$EXTENSION"
    }
}
