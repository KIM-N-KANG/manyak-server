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

-- 채팅별 스냅샷(V67)을 걷어낸다. 프로덕션은 중간 상태를 보지 않는다 — V67이 아직 릴리스되지 않았고 이 작업도
-- 같은 릴리스에 나가므로 운영 입장에선 이 컬럼들이 존재한 적이 없다. 남기면 같은 값의 출처가 둘이 되어 어느 쪽이
-- 정본인지 코드마다 헷갈린다.
--
-- **폴백으로도 못 쓴다.** V67의 백필은 기존 채팅 전부에 *현재* 값을 박았으므로, 이미 비공개인 스토리에서는 그 값이
-- 곧 개작본이다. 감추려던 것을 들고 있어서 스토리 스냅샷이 NULL인 경우와 똑같이 틀리다.
--
-- 함께 잃는 것(수용): reached_ending_name_snapshot이 살리던 "엔딩 행이 삭제돼 reached_ending_id가 NULL이 된
-- 채팅의 서재 도달 기록"은 복구할 수 없게 된다. 스토리 스냅샷은 엔딩 id로 이름을 찾는데, 그 id를 들고 있던
-- 채팅·메시지 쪽이 FK(ON DELETE SET NULL)로 함께 비기 때문이다.
ALTER TABLE story_chats DROP COLUMN story_title_snapshot;
ALTER TABLE story_chats DROP COLUMN story_thumbnail_key_snapshot;
ALTER TABLE story_chats DROP COLUMN story_prologue_snapshot;
ALTER TABLE story_chats DROP COLUMN reached_ending_name_snapshot;
