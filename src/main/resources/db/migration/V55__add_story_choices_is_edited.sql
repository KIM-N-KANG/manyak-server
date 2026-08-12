-- KNK-819: 사용자가 고른 선택지를 원문 그대로 보냈는지, 고쳐서 보냈는지 기록한다.
--
-- is_selected·selected_at은 V5부터 있었지만 값을 쓰는 코드가 없어 운영 데이터가 전부 false·NULL이다.
-- 이번 티켓이 턴 저장 트랜잭션에서 그 두 컬럼을 채우고, 편집 여부를 담을 컬럼을 여기서 추가한다.
--
-- NULL 허용이 의미를 갖는다: "고르지 않았다(직접 입력·구버전 클라이언트·기록 스킵)"와 "골랐고 안 고쳤다(false)"는
-- 다른 사실이다. NOT NULL DEFAULT FALSE로 두면 과거 행과 직접 입력 턴이 전부 "그대로 선택함"으로 오판된다.
-- 백필하지 않는다 — 어느 선택지가 선택됐는지 되살릴 원본 정보가 없다.

ALTER TABLE story_choices
    ADD COLUMN is_edited BOOLEAN;

COMMENT ON COLUMN story_choices.is_edited IS
    'KNK-819: 선택한 선택지 원문과 최종 user_input의 정규화(NFC→trim→내부 공백 런 축약, 구두점 보존) 비교 결과. 고쳐 보냈으면 true, 그대로면 false. 선택 기록이 없으면 NULL.';

COMMENT ON COLUMN story_choices.is_selected IS
    'KNK-819: 이 선택지를 골라 다음 턴을 보냈으면 true. 다음 턴 저장 트랜잭션에서 직전 턴의 해당 행에 기록한다(마지막 턴이 아니거나 순번이 범위 밖이면 기록을 건너뛴다).';

COMMENT ON COLUMN story_choices.selected_at IS
    'KNK-819: is_selected를 true로 기록한 시각. 그 선택으로 진행한 다음 턴이 저장된 시각과 같다.';
