-- KNK-1065: 스토리가 "마지막으로 공개(PUBLISHED∧PUBLIC)였던 시점"의 표시·생성 재료를 통째로 남긴다.
--
-- KNK-1059·1064는 이 값을 **채팅별**로 박았다(V67). 두 가지가 남아 있었다.
-- 1. 프롤로그·제목만 막아서, AI 턴 요청에 실리는 설정·주요 사건·엔딩·장르는 여전히 현재 값이었다. 제작자가
--    스토리를 감추고 뜯어고치는 중이면 그 개작 설정이 생성 결과를 통해 독자에게 흘러갔다.
-- 2. 스냅샷 시점이 **채팅 생성 시점**이라, 공개 상태에서 이뤄진 밸런스 패치(v2)를 비공개 전환 시 v1로
--    되돌려 보여줬다. 유출은 아니지만 독자가 마지막으로 보던 화면과 다르다.
--
-- 스토리별로 하나를 남기면 둘 다 사라진다. 값이 통째로 읽히고 통째로 쓰이므로 관계형으로 펴지 않고 jsonb 하나에 담는다.

-- **stories의 컬럼이 아니라 별도 테이블에 둔다**(PR #224 Codex P2).
-- JPA basic 매핑은 기본이 eager라 stories 행을 뜰 때마다 이 JSON 전체를 읽고 역직렬화한다. 스토리 목록·
-- /stories/batch(최대 100건)·서재·오리지널 목록이 전부 스토리 엔티티를 긁는데 이 값을 쓰지 않고, 설정 본문에
-- 입력 상한도 없어 한 건이 수십 KB까지 커질 수 있다. `@Basic(fetch = LAZY)`는 Hibernate 바이트코드
-- 인핸스먼트가 켜져 있어야 실제로 지연되는데 이 레포는 켜져 있지 않아(빌드에 org.hibernate.orm 플러그인 없음)
-- 조용히 무시된다. 테이블을 나누면 필요한 읽기 경로만 조인 없이 따로 조회한다.
CREATE TABLE story_public_snapshots (
    story_id BIGINT PRIMARY KEY,
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_story_public_snapshots_story
        FOREIGN KEY (story_id) REFERENCES stories (id) ON DELETE CASCADE
);

COMMENT ON TABLE story_public_snapshots IS
    'KNK-1065: 스토리가 마지막으로 공개(PUBLISHED AND PUBLIC)였던 시점의 표시·생성 재료. 읽을 수 없는 스토리를 참조하는 채팅 경로가 현재 값 대신 읽는다.';

-- 백필: **지금 공개인 스토리만** 현재 값으로 채운다. 지금이 곧 마지막 공개 시점이라 정확하다.
-- 이미 비공개·초안·삭제인 스토리는 행을 만들지 않는다 — 그 스토리의 현재 값은 감추려던 개작본이라, 채워 넣으면
-- 이 배포가 막으려는 유출을 그대로 되살린다. 마지막 공개 버전을 복원할 방법은 없다.
--
-- **이 배포는 기존 유출을 소급 복구하지 못하고 앞으로만 막는다.** 스냅샷이 없는 스토리를 참조하는 읽기 경로는
-- 각 필드의 빈 값 처리를 따른다(제목·프롤로그는 빈 문자열, 목록은 빈 목록).
--
-- 키 이름은 com.knk.manyak.story.entity.StoryPublicSnapshot의 프로퍼티명과 정확히 같아야 한다(Jackson 역직렬화).
-- 목록의 순서가 곧 표시 순서다(시작 설정 id, 추천 입력 input_order, 엔딩·주요 사건 sort_order).
-- 엔딩은 활성(enabled = true)만 담는다 — 레거시 비활성 행은 새 컬럼이 NULL이라 앱이 실체화하지 못하고,
-- 턴 후보·상세 조회도 이미 활성만 본다.
INSERT INTO story_public_snapshots (story_id, snapshot)
SELECT
    s.id,
    jsonb_build_object(
        'title', s.title,
        'thumbnailImageKey', s.thumbnail_image_key,
        'thumbnailImageUrl', s.thumbnail_image_url,
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
FROM stories s
WHERE s.status = 'PUBLISHED'
  AND s.visibility = 'PUBLIC'
  AND s.deleted_at IS NULL;

-- 완결 주요 사건의 이름 스냅샷(PR #224 Codex P2).
--
-- story_chat_main_events는 main_event_id가 story_main_events FK **ON DELETE CASCADE**라, 수정 API의
-- mainEvents[] 전체 교체가 행을 지우는 순간 남의 채팅 완결 기록이 통째로 사라진다. 그러면 AI 턴 요청은
-- 스냅샷의 옛 사건을 후보로 보내면서 "이미 완결했다"는 사실은 못 보내, 독자가 이미 지난 사건을 다시 겪는다.
-- 도달 엔딩 이름(reached_ending_name_snapshot)과 같은 구조의 파괴라 같은 방식으로 이름을 남겨 복구한다.
--
-- 목록이라 jsonb 배열이다. story_chats는 서재에서 100건까지 eager로 뜨지만, 이 값은 짧은 사건 이름 몇 개라
-- 같은 행에 이미 있는 story_prologue_snapshot(TEXT 본문) 옆에서 무시할 만한 크기다.
ALTER TABLE story_chats ADD COLUMN occurred_main_event_names_snapshot JSONB;

-- 기존 채팅은 아직 살아 있는 조인 행에서 이름을 옮겨 담는다(표시 순서 = sort_order).
UPDATE story_chats sc
SET occurred_main_event_names_snapshot = names.list
FROM (
    SELECT scme.chat_id, jsonb_agg(sme.name ORDER BY sme.sort_order) AS list
    FROM story_chat_main_events scme
    JOIN story_main_events sme ON sme.id = scme.main_event_id
    GROUP BY scme.chat_id
) names
WHERE sc.id = names.chat_id;

-- **채팅별 스냅샷 컬럼(V67)은 이번 릴리스에서 지우지 않는다.** 읽기 정본만 이 스토리 스냅샷으로 옮기고,
-- DROP은 릴리스가 끝난 뒤 별도 티켓에서 한다(expand/contract).
--
-- 이유 1 — **롤링 배포 중 구버전이 이 컬럼을 읽는다.** ECS 롤링 배포는 새 태스크가 Flyway를 돌리는 동안
-- 구버전 태스크가 계속 요청을 받는다. 구버전 엔티티에 네 컬럼이 매핑돼 있어 여기서 DROP하면 그 창 동안
-- 서재·채팅 상세·공유 열람·이프 이용내역의 SELECT가 통째로 실패해 500이 된다. 컬럼 추가와 달리 DROP은
-- 하위 호환이 아니다. 같은 이유로 배포를 되돌릴 때도 구버전이 그대로 뜬다.
--
-- 이유 2 — **둘은 아직 실제로 쓰인다.** 스토리 스냅샷은 자식을 id로 찾는 사전인데, 수정 API의 전체 교체가
-- 행을 삭제·재생성하면 FK(ON DELETE SET NULL, V41·KNK-515)가 그 조회 키를 비운다. 사전으로는 덮을 수 없다.
--   - reached_ending_name_snapshot: endings[] 교체가 story_chats·story_messages의 reached_ending_id를
--     **동시에** 비운다. 비공개 스토리만의 문제가 아니다 — 공개 스토리에서 제작자가 엔딩을 손보기만 해도
--     그 스토리로 놀던 **모든 독자의 도달 기록**이 날아간다. 서재 폴백으로 계속 읽는다.
--   - story_prologue_snapshot: 편집 폼에서 시작 설정 항목을 빼면 story_chats.start_setting_id가 비어
--     스냅샷의 시작 설정을 찾을 수 없다. 상세·공유·AI 조립의 프롤로그 폴백으로 계속 읽는다.
--
-- 나머지 둘(story_title_snapshot·story_thumbnail_key_snapshot)은 읽지 않지만 채팅 생성 시 계속 채운다.
-- 배포를 되돌리면 구버전이 그 값을 읽기 때문이다. **다음 릴리스의 DROP 대상은 이 둘뿐이다.**

-- **롤링 배포 창의 한계(수용)**: 이 마이그레이션이 적용된 뒤 구버전 태스크가 공개 스토리를 저장하면
-- 스냅샷이 찍히지 않는다(구버전 코드에 갱신 지점이 없다). 창이 배포 한 번이고, 그 스토리를 다음에 공개
-- 상태로 저장하는 순간 자가 복구되며, 그때까지는 백필 값(또는 스냅샷 없음)으로 안전한 쪽에 머문다.
