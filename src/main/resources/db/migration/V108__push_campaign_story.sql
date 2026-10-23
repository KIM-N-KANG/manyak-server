ALTER TABLE push_campaigns ADD COLUMN story_id bigint REFERENCES stories(id);
COMMENT ON COLUMN push_campaigns.story_id IS '프로모션 이동 대상 스토리. 없으면 NULL';
