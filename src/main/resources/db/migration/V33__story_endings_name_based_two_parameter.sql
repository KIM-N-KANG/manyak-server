-- 엔딩을 이름 기반 2파라미터 구조로 재정의한다(스펙 §4-3-10, KNK-462·463, KNK-464).
-- 유형 없이 이름으로 식별하고, 조건은 min_turns(정수, 백엔드 결정적 판정) + achievement_condition(자연어, AI 정성 판정)
-- 2파라미터다(둘 다 충족 AND). goal·main_event_names 분리 구조는 한 필드로 통합됐고, 엔딩↔주요 사건 연결 테이블은
-- 두지 않는다(KNK-463). 도달 시 epilogue 가이드로 엔딩 응답을 생성한다(런타임 연동은 §4-3-10, 별도 범위).
--
-- 레거시 행(title·content·condition_text, KNK-419)은 새 구조로 자동 변환하지 않고 enabled=false로 비활성 보존한다.
-- 자유 텍스트 조건을 구조화 조건으로 기계 변환할 수 없고 대상이 소수라, 수정 화면에서의 수동 재등록이 안전하기 때문이다.
-- 레거시 컬럼은 nullable로 남겨 데이터를 보존하고, 새 컬럼도 nullable로 두어 레거시 행과 공존시킨다.
-- 신규 행은 앱이 새 컬럼을 항상 채우며, 조회는 활성(enabled=true) 행만 읽어 레거시 행을 실체화하지 않는다.

-- 1) 새 컬럼 추가(레거시 행과 공존을 위해 nullable).
ALTER TABLE story_endings ADD COLUMN name VARCHAR(100);
ALTER TABLE story_endings ADD COLUMN min_turns INTEGER;
ALTER TABLE story_endings ADD COLUMN achievement_condition TEXT;
ALTER TABLE story_endings ADD COLUMN epilogue TEXT;

-- 최소 턴 수는 음수가 될 수 없다(NULL 레거시 행은 검사 대상 아님).
ALTER TABLE story_endings ADD CONSTRAINT ck_story_endings_min_turns CHECK (min_turns IS NULL OR min_turns >= 0);

-- 2) 기존(레거시) 행을 전부 비활성 보존한다. 새 컬럼은 NULL로 두고 조회에서 제외(enabled=false)한다.
UPDATE story_endings SET enabled = false;

-- 3) 레거시 필수 컬럼의 NOT NULL을 완화한다. 신규 행은 새 컬럼만 채우고 레거시 컬럼은 NULL로 둔다.
ALTER TABLE story_endings ALTER COLUMN title DROP NOT NULL;
ALTER TABLE story_endings ALTER COLUMN content DROP NOT NULL;
