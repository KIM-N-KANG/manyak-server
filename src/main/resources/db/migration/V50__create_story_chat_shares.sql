-- KNK-706: 채팅 공유 링크(스펙 §4-3-11).
-- 발급 시점의 story_chats.current_turn을 커트라인으로 기록하는 시점 고정 방식이다 — 메시지를 복사하지 않고
-- 열람 시 커트라인 이하 턴만 조립한다. 삭제 컬럼을 두지 않아 공유의 유효성은 원본 채팅 deleted_at에 종속된다
-- (공유 해지 수단은 채팅 삭제뿐).
CREATE TABLE story_chat_shares (
    id          BIGSERIAL PRIMARY KEY,
    -- 공유 열람 토큰. 추측 불가 UUID v4 보유가 곧 접근 수단이므로 채팅 public_id와 무관한 별도 값이다(§4-4 식별자 정책).
    public_id   UUID NOT NULL,
    chat_id     BIGINT NOT NULL,
    turn_cutoff INT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_story_chat_shares_public_id UNIQUE (public_id),
    -- 멱등 재발급: 같은 커트라인으로 다시 발급하면 새로 만들지 않고 기존 공유를 반환한다(중복 클릭 안전).
    CONSTRAINT uq_story_chat_shares_chat_cutoff UNIQUE (chat_id, turn_cutoff),
    -- 채팅이 물리 삭제되면 공유도 함께 정리한다(고아 행 방지). 소프트 삭제는 열람 시 404로 처리한다.
    CONSTRAINT fk_story_chat_shares_chat
        FOREIGN KEY (chat_id) REFERENCES story_chats (id) ON DELETE CASCADE
);
-- chat_id 단독 조회는 uq_story_chat_shares_chat_cutoff의 선두 컬럼 인덱스가 커버하므로 별도 인덱스를 두지 않는다.
