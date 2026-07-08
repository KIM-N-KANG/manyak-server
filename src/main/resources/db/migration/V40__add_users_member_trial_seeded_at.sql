-- 회원 체험 스냅샷 1회성·재시도 마커(B13, 스펙 §4-3-7). NULL이면 아직 스냅샷 전(신규 가입) — 로그인이 게스트
-- 디바이스 사용량을 회원 카운터로 시드하고, 성공 시 이 값을 기록해 이후 로그인이 다시 시드하지 않게 한다.
-- 스냅샷이 Redis 장애로 실패하면 값이 NULL로 남아 다음 로그인이 재시도한다(로그인 자체는 진행).
ALTER TABLE users ADD COLUMN member_trial_seeded_at TIMESTAMPTZ;

-- 롤아웃 이전 가입한 회원은 스냅샷 대상이 아니다: 시드 완료로 표시해 이후 로그인이 잔여 회원 체험을 훼손하지 않게 한다.
-- (기존 회원은 회원 카운터가 미설정이라 기본 full 체험을 받는다. 신규 가입분만 이 값이 NULL로 시작한다.)
UPDATE users SET member_trial_seeded_at = now();
