CREATE TABLE admin_analytics_daily_values (
    id BIGSERIAL PRIMARY KEY,
    metric VARCHAR(64) NOT NULL,
    params_hash CHAR(64) NOT NULL,
    params JSONB NOT NULL,
    metric_date DATE NOT NULL,
    "value" JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    CONSTRAINT uk_admin_analytics_daily_values_key UNIQUE (metric, params_hash, metric_date)
);
CREATE TABLE admin_analytics_range_values (
    id BIGSERIAL PRIMARY KEY,
    metric VARCHAR(64) NOT NULL,
    params_hash CHAR(64) NOT NULL,
    params JSONB NOT NULL,
    range_from DATE NOT NULL,
    range_to DATE NOT NULL,
    "value" JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    CONSTRAINT ck_admin_analytics_range_values_dates CHECK (range_from <= range_to),
    CONSTRAINT uk_admin_analytics_range_values_key UNIQUE (metric, params_hash, range_from, range_to)
);
