-- 게스트 데이터 이관(POST /api/v1/auth/migrate)을 완료한 시각(스펙 §4-3-5, KNK-480 · KNK-434).
-- 이관은 계정당 1회만 허용한다: 한 요청으로 한 건이라도 소유권을 얻으면(≥1건 MIGRATED) 이 값을 기록해 계정을 잠근다.
-- 값이 있으면 이후 migrate 호출은 어떤 항목도 평가하지 않고 migrationClosed=true로 닫는다(기기 교체 반복 이관 어뷰징 차단).
-- NULL이면 아직 이관하지 않음(최초 이관 가능). 대다수 계정은 이관 이력이 없어 인덱스는 두지 않는다(단건 PK 조회로만 참조).
ALTER TABLE users ADD COLUMN migrated_at TIMESTAMPTZ;
