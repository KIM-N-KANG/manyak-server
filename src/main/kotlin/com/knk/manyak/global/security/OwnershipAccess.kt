package com.knk.manyak.global.security

/**
 * 게스트 ↔ 로그인 회원 교차 접근 차단 규칙(스펙 §4-5, KNK-480). 리소스 소유자([ownerId])와 요청자([requesterId])로
 * 소유 리소스에 대한 행위(플레이·수정·삭제·상세 조회 등) 허용 여부를 판정한다.
 *
 * - 게스트 리소스([ownerId] == null): 게스트 요청([requesterId] == null)만 허용한다. **인증 회원은 차단(403)** —
 *   회원은 먼저 데이터 이관(POST /api/v1/auth/migrate)으로 소유권을 얻은 뒤 접근해야 한다.
 * - 회원 리소스([ownerId] != null): 소유자([requesterId] == [ownerId])만 허용한다. 타인·미인증은 차단(403).
 *
 * 게스트 ↔ 게스트는 서버에 게스트 식별 수단이 없어(콘텐츠 행에 device_id 미저장) 구분할 수 없으므로 열려 있음을 수용한다(스펙 명시).
 * 공개 스토리 읽기(PUBLISHED∧PUBLIC)는 이 게이트가 아니라 [com.knk.manyak.story.entity.Story.isReadableBy]가 담당하며 여기서 다루지 않는다.
 */
fun isOwnerAccessAllowed(ownerId: Long?, requesterId: Long?): Boolean =
    if (ownerId == null) requesterId == null else ownerId == requesterId
