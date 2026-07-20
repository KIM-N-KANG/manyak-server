# public.stories

## Columns

| Name | Type | Default | Nullable | Children | Parents | Comment |
| ---- | ---- | ------- | -------- | -------- | ------- | ------- |
| id | bigint | nextval('stories_id_seq'::regclass) | false | [public.story_settings](public.story_settings.md) [public.story_start_settings](public.story_start_settings.md) [public.story_chats](public.story_chats.md) [public.story_lorebooks](public.story_lorebooks.md) [public.story_main_events](public.story_main_events.md) [public.user_story_ending_reaches](public.user_story_ending_reaches.md) [public.story_images](public.story_images.md) [public.story_characters](public.story_characters.md) |  |  |
| user_id | bigint |  | true |  |  |  |
| title | varchar(100) |  | false |  |  |  |
| one_line_intro | varchar(255) |  | true |  |  |  |
| description | text |  | true |  |  |  |
| genre | varchar(255) |  | true |  |  |  |
| created_at | timestamp with time zone | now() | false |  |  |  |
| updated_at | timestamp with time zone | now() | false |  |  |  |
| deleted_at | timestamp with time zone |  | true |  |  |  |
| public_id | uuid | gen_random_uuid() | false |  |  |  |
| status | varchar(20) | 'PUBLISHED'::character varying | false |  |  |  |
| visibility | varchar(20) | 'PUBLIC'::character varying | false |  |  |  |
| thumbnail_image_key | varchar(64) |  | true |  | [public.image_presets](public.image_presets.md) |  |

## Constraints

| Name | Type | Definition |
| ---- | ---- | ---------- |
| ck_stories_status | CHECK | CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying])::text[]))) |
| ck_stories_visibility | CHECK | CHECK (((visibility)::text = ANY ((ARRAY['PUBLIC'::character varying, 'PRIVATE'::character varying])::text[]))) |
| stories_pkey | PRIMARY KEY | PRIMARY KEY (id) |
| uq_stories_public_id | UNIQUE | UNIQUE (public_id) |
| fk_stories_thumbnail_image_key | FOREIGN KEY | FOREIGN KEY (thumbnail_image_key) REFERENCES image_presets(image_key) |

## Indexes

| Name | Definition |
| ---- | ---------- |
| stories_pkey | CREATE UNIQUE INDEX stories_pkey ON public.stories USING btree (id) |
| uq_stories_public_id | CREATE UNIQUE INDEX uq_stories_public_id ON public.stories USING btree (public_id) |
| idx_stories_user_created | CREATE INDEX idx_stories_user_created ON public.stories USING btree (user_id, created_at DESC, id DESC) WHERE (deleted_at IS NULL) |

## Relations

```mermaid
erDiagram

"public.story_settings" |o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_start_settings" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_chats" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_lorebooks" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_main_events" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.user_story_ending_reaches" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_images" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_characters" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.stories" }o--o| "public.image_presets" : "FOREIGN KEY (thumbnail_image_key) REFERENCES image_presets(image_key)"

"public.stories" {
  bigint id
  bigint user_id
  varchar_100_ title
  varchar_255_ one_line_intro
  text description
  varchar_255_ genre
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
  timestamp_with_time_zone deleted_at
  uuid public_id
  varchar_20_ status
  varchar_20_ visibility
  varchar_64_ thumbnail_image_key FK
}
"public.story_settings" {
  bigint id
  bigint story_id FK
  text world_setting
  text character_setting
  text user_role_setting
  text rule_setting
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
}
"public.story_start_settings" {
  bigint id
  bigint story_id FK
  varchar_100_ name
  text prologue
  text start_situation
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
  uuid public_id
}
"public.story_chats" {
  bigint id
  bigint user_id
  bigint story_id FK
  bigint start_setting_id FK
  varchar_100_ title
  text summary
  integer current_turn
  varchar_20_ status
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
  timestamp_with_time_zone deleted_at
  uuid public_id
  integer regenerated_count
  bigint target_main_event_id FK
  integer target_progress_turns
  bigint reached_ending_id FK
}
"public.story_lorebooks" {
  bigint id
  bigint story_id FK
  bigint lorebook_id FK
  smallint sort_order
  timestamp_with_time_zone created_at
}
"public.story_main_events" {
  bigint id
  bigint story_id FK
  varchar_100_ name
  text description
  text key_sentence
  smallint sort_order
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
}
"public.user_story_ending_reaches" {
  bigint id
  bigint user_id FK
  bigint story_id FK
  bigint ending_id FK
  timestamp_with_time_zone created_at
}
"public.story_images" {
  bigint id
  bigint story_id FK
  varchar_64_ image_key FK
  timestamp_with_time_zone created_at
}
"public.story_characters" {
  bigint id
  bigint story_id FK
  varchar_50_ name
  varchar_64_ image_key FK
  timestamp_with_time_zone created_at
}
"public.image_presets" {
  bigint id
  varchar_64_ image_key
  varchar_20_ type
  varchar_50_ mood
  varchar_50_ subject
  varchar_50_ prop
  timestamp_with_time_zone deactivated_at
  timestamp_with_time_zone created_at
}
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
