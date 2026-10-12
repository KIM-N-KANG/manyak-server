\set ON_ERROR_STOP on
\if :{?user_count}
\else
\set user_count 500
\endif
\if :{?credits}
\else
\set credits 1000000
\endif
\if :{?terms_version}
\else
\set terms_version v1.4
\endif
\if :{?privacy_version}
\else
\set privacy_version v1.7
\endif
BEGIN;
DO $$ BEGIN
  IF current_setting('manyak.loadtest_guard', true) IS DISTINCT FROM 'manyak-loadtest-pg' THEN
    RAISE EXCEPTION 'Refusing seed: loadtest session guard is missing';
  END IF;
END $$;
-- Serialize repeat seed runs; do not reset balances/consumption on rerun.
SELECT pg_advisory_xact_lock(1596);
CREATE TEMP TABLE seed_config AS SELECT :user_count::integer AS n, :credits::bigint AS credits;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM seed_config WHERE n NOT BETWEEN 1 AND 100000 OR credits <= 0) THEN
    RAISE EXCEPTION 'Invalid user_count or credits';
  END IF;
END $$;
CREATE TEMP TABLE seed_ids AS
SELECT ('15960000-0000-4000-8000-' || lpad(i::text,12,'0'))::uuid AS public_id,
       'k6loadtest' || lpad(i::text,6,'0') AS nickname
FROM generate_series(1, :user_count) AS i;
INSERT INTO users(public_id,nickname,status,member_trial_seeded_at)
SELECT public_id,nickname,'ACTIVE',now() FROM seed_ids
ON CONFLICT(public_id) DO NOTHING;
CREATE TEMP TABLE seed_users AS
SELECT u.id,u.public_id FROM users u JOIN seed_ids s USING(public_id);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM users u JOIN seed_ids s USING(public_id)
             WHERE u.status <> 'ACTIVE' OR u.deleted_at IS NOT NULL OR u.nickname <> s.nickname) THEN
    RAISE EXCEPTION 'Reserved seed identity collision or inactive seed account';
  END IF;
END $$;
INSERT INTO user_consents(user_id,doc_type,version)
SELECT u.id,c.doc_type,c.version FROM seed_users u
CROSS JOIN (VALUES ('TERMS', :'terms_version'), ('PRIVACY', :'privacy_version'), ('AGE14','1')) c(doc_type,version)
ON CONFLICT DO NOTHING;
INSERT INTO credit_wallets(user_id) SELECT id FROM seed_users ON CONFLICT(user_id) DO NOTHING;
CREATE TEMP TABLE seed_grants AS
WITH grants AS (
  INSERT INTO credit_transactions(user_id,amount,reason,idempotency_key)
  SELECT id,:credits,'PURCHASE','k6-seed:' || public_id FROM seed_users
  ON CONFLICT(idempotency_key) DO NOTHING
  RETURNING id,user_id,amount
) SELECT * FROM grants;
INSERT INTO credit_lots(user_id,transaction_id,original_amount,remaining,expires_at)
SELECT user_id,id,amount,amount,NULL FROM seed_grants;
UPDATE credit_wallets w SET balance=w.balance+g.amount,updated_at=now()
FROM seed_grants g WHERE w.user_id=g.user_id;
COMMIT;
