ALTER TABLE chat_models
    ADD COLUMN promo_turn_cost bigint CHECK (promo_turn_cost BETWEEN 0 AND 10000),
    ADD COLUMN promo_starts_at timestamptz,
    ADD COLUMN promo_ends_at timestamptz,
    ADD CONSTRAINT ck_chat_models_promo CHECK (
        (promo_turn_cost IS NULL AND promo_starts_at IS NULL AND promo_ends_at IS NULL)
        OR (promo_turn_cost IS NOT NULL AND promo_starts_at IS NOT NULL AND promo_ends_at IS NOT NULL
            AND promo_ends_at > promo_starts_at)
    );

COMMENT ON COLUMN chat_models.promo_turn_cost IS '프로모션 기간의 회원 턴당 이프 (0은 무료)';
COMMENT ON COLUMN chat_models.promo_starts_at IS '프로모션 시작 시각 (포함)';
COMMENT ON COLUMN chat_models.promo_ends_at IS '프로모션 종료 시각 (제외)';
