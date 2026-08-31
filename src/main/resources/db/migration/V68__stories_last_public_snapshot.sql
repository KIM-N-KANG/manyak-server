-- KNK-1065: 스토리가 "마지막으로 공개(PUBLISHED∧PUBLIC)였던 시점"의 표시·생성 재료를 스토리 행에 통째로 남긴다.
--
-- KNK-1059·1064는 이 값을 **채팅별**로 박았다(V67). 두 가지가 남아 있었다.
-- 1. 프롤로그·제목만 막아서, AI 턴 요청에 실리는 설정·주요 사건·엔딩·장르는 여전히 현재 값이었다. 제작자가
--    스토리를 감추고 뜯어고치는 중이면 그 개작 설정이 생성 결과를 통해 독자에게 흘러갔다.
-- 2. 스냅샷 시점이 **채팅 생성 시점**이라, 공개 상태에서 이뤄진 밸런스 패치(v2)를 비공개 전환 시 v1로
--    되돌려 보여줬다. 유출은 아니지만 독자가 마지막으로 보던 화면과 다르다.
--
-- 스토리별로 하나를 남기면 둘 다 사라진다. 값이 통째로 읽히고 통째로 쓰이므로 관계형으로 펴지 않고 jsonb 하나에 담는다.
-- 게이트 판정(stories 행)과 표시 값이 같은 행에서 읽히는 부수 효과도 있다.
ALTER TABLE stories ADD COLUMN last_public_snapshot JSONB;

-- 백필: **지금 공개인 스토리만** 현재 값으로 채운다. 지금이 곧 마지막 공개 시점이라 정확하다.
-- 이미 비공개·초안·삭제인 스토리는 NULL로 둔다 — 그 스토리의 현재 값은 감추려던 개작본이라, 채워 넣으면
-- 이 배포가 막으려는 유출을 그대로 되살린다. 마지막 공개 버전을 복원할 방법은 없다.
--
-- **이 배포는 기존 유출을 소급 복구하지 못하고 앞으로만 막는다.** 스냅샷이 NULL인 스토리를 참조하는 읽기 경로는
-- 각 필드의 빈 값 처리를 따른다(제목·프롤로그는 빈 문자열, 목록은 빈 목록).
--
-- 키 이름은 com.knk.manyak.story.entity.StoryPublicSnapshot의 프로퍼티명과 정확히 같아야 한다(Jackson 역직렬화).
-- 목록의 순서가 곧 표시 순서다(시작 설정 id, 추천 입력 input_order, 엔딩·주요 사건 sort_order).
-- 엔딩은 활성(enabled = true)만 담는다 — 레거시 비활성 행은 새 컬럼이 NULL이라 앱이 실체화하지 못하고,
-- 턴 후보·상세 조회도 이미 활성만 본다.
UPDATE stories s
SET last_public_snapshot = jsonb_build_object(
    'title', s.title,
    'thumbnailImageKey', s.thumbnail_image_key,
    'genre', s.genre,
    'storySettings', COALESCE(
        (
            SELECT jsonb_build_object(
                'worldSetting', st.world_setting,
                'characterSetting', st.character_setting,
                'userRoleSetting', st.user_role_setting,
                'ruleSetting', st.rule_setting
            )
            FROM story_settings st
            WHERE st.story_id = s.id
        ),
        '{}'::jsonb
    ),
    'startSettings', COALESCE(
        (
            SELECT jsonb_agg(
                jsonb_build_object(
                    'id', ss.id,
                    'name', ss.name,
                    'prologue', ss.prologue,
                    'startSituation', ss.start_situation,
                    'suggestedInputs', COALESCE(
                        (
                            SELECT jsonb_agg(si.input_text ORDER BY si.input_order)
                            FROM story_suggested_inputs si
                            WHERE si.start_setting_id = ss.id
                        ),
                        '[]'::jsonb
                    ),
                    'endings', COALESCE(
                        (
                            SELECT jsonb_agg(
                                jsonb_build_object(
                                    'id', se.id,
                                    'name', se.name,
                                    'minTurns', se.min_turns,
                                    'achievementCondition', se.achievement_condition,
                                    'epilogue', se.epilogue
                                ) ORDER BY se.sort_order
                            )
                            FROM story_endings se
                            WHERE se.start_setting_id = ss.id AND se.enabled = TRUE
                        ),
                        '[]'::jsonb
                    )
                ) ORDER BY ss.id
            )
            FROM story_start_settings ss
            WHERE ss.story_id = s.id
        ),
        '[]'::jsonb
    ),
    'mainEvents', COALESCE(
        (
            SELECT jsonb_agg(
                jsonb_build_object(
                    'id', sme.id,
                    'name', sme.name,
                    'description', sme.description,
                    'keySentence', sme.key_sentence
                ) ORDER BY sme.sort_order
            )
            FROM story_main_events sme
            WHERE sme.story_id = s.id
        ),
        '[]'::jsonb
    )
)
WHERE s.status = 'PUBLISHED'
  AND s.visibility = 'PUBLIC'
  AND s.deleted_at IS NULL;

-- **채팅별 스냅샷 컬럼(V67)은 이번 릴리스에서 지우지 않는다.** 읽기 정본만 이 스토리 스냅샷으로 옮기고,
-- DROP은 릴리스가 끝난 뒤 별도 티켓에서 한다(expand/contract).
--
-- 이유 1 — **롤링 배포 중 구버전이 이 컬럼을 읽는다.** ECS 롤링 배포는 새 태스크가 Flyway를 돌리는 동안
-- 구버전 태스크가 계속 요청을 받는다. 구버전 엔티티에 네 컬럼이 매핑돼 있어 여기서 DROP하면 그 창 동안
-- 서재·채팅 상세·공유 열람·이프 이용내역의 SELECT가 통째로 실패해 500이 된다. 컬럼 추가와 달리 DROP은
-- 하위 호환이 아니다. 같은 이유로 배포를 되돌릴 때도 구버전이 그대로 뜬다.
--
-- 이유 2 — **reached_ending_name_snapshot은 아직 실제로 쓰인다.** 스토리 스냅샷은 "엔딩 id → 이름" 사전인데,
-- 수정 API의 endings[] 전체 교체가 행을 삭제·재생성하면 FK(ON DELETE SET NULL, V41)가 story_chats·
-- story_messages의 reached_ending_id를 **동시에** 비운다. 사전을 조회할 키가 사라지므로 사전으로는 덮을 수
-- 없다. 이건 비공개 스토리만의 문제가 아니다 — 공개 스토리에서 제작자가 엔딩을 손보기만 해도 그 스토리로
-- 놀던 **모든 독자의 도달 기록**이 날아간다. 그래서 이 컬럼은 서재 폴백으로 계속 읽는다.
--
-- 나머지 셋(story_title_snapshot·story_thumbnail_key_snapshot·story_prologue_snapshot)은 읽지 않지만
-- 채팅 생성 시 계속 채운다. 배포를 되돌리면 구버전이 그 값을 읽기 때문이다. 다음 릴리스의 DROP 대상이다.
