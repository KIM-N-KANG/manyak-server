ALTER TABLE credit_orders DROP CONSTRAINT ck_credit_orders_provider;
ALTER TABLE credit_orders ADD CONSTRAINT ck_credit_orders_provider
    CHECK (provider IN ('GROBLE'));
