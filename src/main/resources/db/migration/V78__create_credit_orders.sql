-- KNK-1156: 상품별 웹 충전 주문. 상품 정의는 서버 설정이며 별도 상품 테이블은 두지 않는다.
CREATE TABLE credit_orders (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    product_id VARCHAR(32) NOT NULL,
    provider VARCHAR(20) NOT NULL CONSTRAINT ck_credit_orders_provider
        CHECK (provider IN ('GROBLE', 'GOOGLE_PLAY')),
    status VARCHAR(20) NOT NULL CONSTRAINT ck_credit_orders_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'REFUNDED')),
    price_krw BIGINT NOT NULL,
    credit_amount BIGINT NOT NULL,
    provider_ref VARCHAR(255) UNIQUE,
    credit_transaction_id BIGINT REFERENCES credit_transactions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ
);

CREATE INDEX idx_credit_orders_user_created_at ON credit_orders (user_id, created_at DESC);
