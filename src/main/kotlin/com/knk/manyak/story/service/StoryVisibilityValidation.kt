package com.knk.manyak.story.service

import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.story.entity.StoryVisibility
import org.springframework.http.HttpStatus

/**
 * 게스트(소유자 없음) 스토리의 공개 지정을 막는다(KNK-149).
 *
 * 공개 목록(`GET /stories`)이 회원 소유 스토리만 싣는 것과 같은 이유다 — 작성자 신원이 없어 카드에 작성자를
 * 표기할 수 없고, 좋아요·신고 같은 소셜 기능의 책임 주체가 없다. 목록에서 거르는 것만으로는 부족하다:
 * 소유자 없는 PUBLIC 스토리는 상세·채팅 경로에서 여전히 열려 있고, 데이터에 남아 있으면 다음 소비자가
 * 같은 판정을 또 해야 한다. 만들 때 막는 편이 상태를 단순하게 유지한다.
 *
 * **조용히 PRIVATE으로 낮추지 않고 400으로 거부한다.** 사용자가 고른 값을 서버가 몰래 뒤집으면 나중에
 * "공개했는데 왜 안 보이냐"가 된다. 공개는 로그인해 이관(소유권 획득)한 뒤에 하면 된다.
 *
 * 간편 제작은 회원·게스트 구분 없이 PRIVATE으로 저장하므로([SimpleStoryCreationService], KNK-464)
 * 이 검증의 실제 대상은 `visibility`를 요청으로 받는 일반 제작 등록과 스토리 수정이다.
 */
fun requireOwnerCanPublish(ownerUserId: Long?, requested: StoryVisibility?) {
    if (requested == StoryVisibility.PUBLIC && ownerUserId == null) {
        throw CodedResponseStatusException(
            HttpStatus.BAD_REQUEST,
            ApiErrorCodes.GUEST_CANNOT_PUBLISH,
            "게스트는 스토리를 공개할 수 없습니다. 로그인 후 공개해 주세요.",
        )
    }
}
