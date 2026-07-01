-- ============================================================================
-- Manyak 스키마 로드맵 (목표 설계 스냅샷)
--
-- 출처: ERDCloud (https://www.erdcloud.com/d/CuTb5GzGabvHhihZp), 2026-06-26 기준 동결.
-- 성격: "앞으로 만들 목표 스키마"의 수기 설계 스냅샷이며, 현행 실제 스키마가 아니다.
--   - 현행(실제) 스키마 문서: tbls 자동 생성 `dbdoc/` (Flyway 진실원본 기준)
--   - 목표(설계) 스키마: 이 파일 (수기, 새 기능 설계 시 갱신)
-- ERDCloud는 유지보수를 중단(freeze)하고, 설계 의도 보존을 위해 이 파일로 박제한다.
-- 주의: 일부 표기는 ERDCloud export 그대로다(예: `사용자.deleted_at NOT NULL`은 오기,
--   FK는 export에 포함되지 않음). 실제 구현은 Flyway 마이그레이션을 진실원본으로 따른다.
-- ============================================================================

CREATE TABLE `스토리 작성 예시` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`story_id`	BIGINT	NOT NULL,
	`example_text`	TEXT	NOT NULL,
	`sort_order`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `키워드 트리거` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`keyword_note_id`	BIGINT	NOT NULL,
	`keyword`	VARCHAR(100)	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `메모리 문서` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`play_session_id`	BIGINT	NOT NULL,
	`last_updated_message_id`	BIGINT	NOT NULL,
	`content`	TEXT	NOT NULL,
	`summary`	TEXT	NULL,
	`revision`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `추천 입력` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`start_setting_id`	BIGINT	NOT NULL,
	`input_text`	TEXT	NOT NULL,
	`sort_order`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스토리` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`creator_user_id`	BIGINT	NOT NULL,
	`title`	VARCHAR(100)	NOT NULL,
	`one_line_intro`	VARCHAR(255)	NULL,
	`description`	TEXT	NULL,
	`thumbnail_url`	TEXT	NULL,
	`thumbnail_base64`	TEXT	NULL,
	`genre`	VARCHAR(50)	NULL,
	`visibility`	VARCHAR(20)	NOT NULL	COMMENT 'PUBLIC, PRIVATE',
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'DRAFT, PUBLISHED, ARCHIVED, DELETED',
	`play_count`	INTEGER	NOT NULL,
	`like_count`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL,
	`deleted_at`	TIMESTAMPTZ	NULL
);

CREATE TABLE `선택지` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`play_session_id`	BIGINT	NOT NULL,
	`message_id`	BIGINT	NOT NULL,
	`choice_text`	TEXT	NOT NULL,
	`choice_order`	INTEGER	NOT NULL,
	`is_selected`	BOOLEAN	NOT NULL,
	`selected_at`	TIMESTAMPTZ	NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `소셜 계정` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`user_id`	BIGINT	NOT NULL,
	`provider`	VARCHAR(20)	NOT NULL	COMMENT 'KAKAO, GOOGLE, APPLE, NAVER',
	`provider_user_id`	VARCHAR(255)	NOT NULL,
	`email`	VARCHAR(255)	NULL,
	`connected_at`	TIMESTAMPTZ	NOT NULL,
	`last_login_at`	TIMESTAMPTZ	NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `플레이 세션` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`user_id`	BIGINT	NOT NULL,
	`story_id`	BIGINT	NOT NULL,
	`start_setting_id`	BIGINT	NOT NULL,
	`title`	VARCHAR(100)	NULL,
	`summary`	TEXT	NULL,
	`current_turn`	INTEGER	NOT NULL,
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'ACTIVE, ARCHIVED',
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL,
	`deleted_at`	TIMESTAMPTZ	NULL
);

CREATE TABLE `사용자` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`nickname`	VARCHAR(50)	NOT NULL,
	`profile_image_url`	TEXT	NULL,
	`profile_thumbnail_base64`	TEXT	NULL,
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'ACTIVE, SUSPENDED, DELETED',
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL,
	`deleted_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `키워드 노트` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`story_id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`content`	TEXT	NOT NULL,
	`enabled`	BOOLEAN	NOT NULL,
	`insertion_order`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스토리 설정` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`story_id`	BIGINT	NOT NULL,
	`world_setting`	TEXT	NOT NULL,
	`character_setting`	TEXT	NOT NULL,
	`user_role_setting`	TEXT	NULL,
	`rule_setting`	TEXT	NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `메시지` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`play_session_id`	BIGINT	NOT NULL,
	`role`	VARCHAR	NOT NULL	COMMENT 'USER, ASSISTANT, SYSTEM',
	`content`	TEXT	NOT NULL,
	`message_order`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스토리 생성 예시 질문` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`example_id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`info_text`	TEXT	NOT NULL,
	`info_order`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `AI 생성 요청 로그` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`user_id`	BIGINT	NOT NULL,
	`story_id`	BIGINT	NULL,
	`play_session_id`	BIGINT	NULL,
	`creation_session_id`	BIGINT	NULL,
	`request_message_id`	BIGINT	NULL,
	`response_message_id`	BIGINT	NULL,
	`request_type`	VARCHAR(30)	NOT NULL	COMMENT 'STORY, CHOICE, SUMMARY, MODERATION, STORY_EXAMPLE, STORY_DRAFT_FIELDS',
	`prompt_tokens`	INTEGER	NULL,
	`completion_tokens`	INTEGER	NULL,
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'SUCCESS, FAILED',
	`message`	TEXT	NULL,
	`trace_id`	VARCHAR(100)	NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스토리 생성 예시` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`creation_session_id`	BIGINT	NOT NULL,
	`title`	VARCHAR(100)	NULL,
	`example_text`	VARCHAR(500)	NOT NULL,
	`example_order`	INTEGER	NOT NULL	COMMENT '1~3',
	`is_selected`	BOOLEAN	NOT NULL	COMMENT '사용자가 선택한 예시 여부, 세션당 true는 최대 1개',
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스토리 생성 태그` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`tag_type`	VARCHAR(30)	NOT NULL	COMMENT 'GENRE, CHARACTER, PROTAGONIST',
	`name`	VARCHAR(50)	NOT NULL,
	`source`	VARCHAR(10)	NOT NULL	COMMENT 'PREDEFINED, CUSTOM',
	`sort_order`	INTEGER	NOT NULL,
	`is_active`	BOOLEAN	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `메모리 리비전` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`memory_document_id`	BIGINT	NOT NULL,
	`source_message_id`	BIGINT	NULL,
	`revision`	INTEGER	NOT NULL,
	`content`	TEXT	NOT NULL,
	`update_reason`	VARCHAR(100)	NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스토리 생성 세션` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`user_id`	BIGINT	NOT NULL,
	`story_id`	BIGINT	NULL,
	`status`	VARCHAR(30)	NOT NULL	COMMENT 'TAG_SELECTED, EXAMPLES_GENERATED, EXAMPLE_SELECTED, DRAFT_CREATED, CANCELED',
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스토리 생성 세션 태그` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`creation_session_id`	BIGINT	NOT NULL,
	`tag_id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`created_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `플레이 스탯` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`play_session_id`	BIGINT	NOT NULL,
	`stat_id`	BIGINT	NOT NULL,
	`current_value`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `시작 설정` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`story_id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NOT NULL,
	`prologue`	TEXT	NULL,
	`opening_scene`	TEXT	NOT NULL,
	`start_situation`	TEXT	NULL,
	`first_ai_message`	TEXT	NULL,
	`play_guide`	TEXT	NULL,
	`sort_order`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

CREATE TABLE `스탯` (
	`id`	BIGINT	NOT NULL	COMMENT 'auto_increment',
	`start_setting_id`	BIGINT	NOT NULL,
	`name`	VARCHAR(50)	NOT NULL,
	`min_value`	INTEGER	NOT NULL,
	`max_value`	INTEGER	NOT NULL,
	`default_value`	INTEGER	NOT NULL,
	`prompt_rule`	TEXT	NULL,
	`sort_order`	INTEGER	NOT NULL,
	`created_at`	TIMESTAMPTZ	NOT NULL,
	`updated_at`	TIMESTAMPTZ	NOT NULL
);

ALTER TABLE `스토리 작성 예시` ADD CONSTRAINT `PK_스토리 작성 예시` PRIMARY KEY (`id`);
ALTER TABLE `키워드 트리거` ADD CONSTRAINT `PK_키워드 트리거` PRIMARY KEY (`id`);
ALTER TABLE `메모리 문서` ADD CONSTRAINT `PK_메모리 문서` PRIMARY KEY (`id`);
ALTER TABLE `추천 입력` ADD CONSTRAINT `PK_추천 입력` PRIMARY KEY (`id`);
ALTER TABLE `스토리` ADD CONSTRAINT `PK_스토리` PRIMARY KEY (`id`);
ALTER TABLE `선택지` ADD CONSTRAINT `PK_선택지` PRIMARY KEY (`id`);
ALTER TABLE `소셜 계정` ADD CONSTRAINT `PK_소셜 계정` PRIMARY KEY (`id`);
ALTER TABLE `플레이 세션` ADD CONSTRAINT `PK_플레이 세션` PRIMARY KEY (`id`);
ALTER TABLE `사용자` ADD CONSTRAINT `PK_사용자` PRIMARY KEY (`id`);
ALTER TABLE `키워드 노트` ADD CONSTRAINT `PK_키워드 노트` PRIMARY KEY (`id`);
ALTER TABLE `스토리 설정` ADD CONSTRAINT `PK_스토리 설정` PRIMARY KEY (`id`);
ALTER TABLE `메시지` ADD CONSTRAINT `PK_메시지` PRIMARY KEY (`id`);
ALTER TABLE `스토리 생성 예시 질문` ADD CONSTRAINT `PK_스토리 생성 예시 질문` PRIMARY KEY (`id`);
ALTER TABLE `AI 생성 요청 로그` ADD CONSTRAINT `PK_AI 생성 요청 로그` PRIMARY KEY (`id`);
ALTER TABLE `스토리 생성 예시` ADD CONSTRAINT `PK_스토리 생성 예시` PRIMARY KEY (`id`);
ALTER TABLE `스토리 생성 태그` ADD CONSTRAINT `PK_스토리 생성 태그` PRIMARY KEY (`id`);
ALTER TABLE `메모리 리비전` ADD CONSTRAINT `PK_메모리 리비전` PRIMARY KEY (`id`);
ALTER TABLE `스토리 생성 세션` ADD CONSTRAINT `PK_스토리 생성 세션` PRIMARY KEY (`id`);
ALTER TABLE `스토리 생성 세션 태그` ADD CONSTRAINT `PK_스토리 생성 세션 태그` PRIMARY KEY (`id`);
ALTER TABLE `플레이 스탯` ADD CONSTRAINT `PK_플레이 스탯` PRIMARY KEY (`id`);
ALTER TABLE `시작 설정` ADD CONSTRAINT `PK_시작 설정` PRIMARY KEY (`id`);
ALTER TABLE `스탯` ADD CONSTRAINT `PK_스탯` PRIMARY KEY (`id`);
