# public.story_messages

## Columns

| Name | Type | Default | Nullable | Children | Parents | Comment |
| ---- | ---- | ------- | -------- | -------- | ------- | ------- |
| id | bigint | nextval('story_messages_id_seq'::regclass) | false | [public.story_choices](public.story_choices.md) [public.story_message_versions](public.story_message_versions.md) |  |  |
| chat_id | bigint |  | false |  | [public.story_chats](public.story_chats.md) |  |
| role | varchar(16) |  | false |  |  |  |
| content | text |  | false |  |  |  |
| message_order | integer |  | false |  |  |  |
| created_at | timestamp with time zone | now() | false |  |  |  |
| reached_ending_id | bigint |  | true |  | [public.story_endings](public.story_endings.md) |  |
| reached_ending_name_snapshot | varchar(100) |  | true |  |  |  |
| credit_transaction_id | bigint |  | true |  | [public.credit_transactions](public.credit_transactions.md) |  |
| model_code | varchar(20) |  | true |  | [public.chat_models](public.chat_models.md) |  |

## Constraints

| Name | Type | Definition |
| ---- | ---- | ---------- |
| ck_story_messages_order | CHECK | CHECK ((message_order > 0)) |
| ck_story_messages_role | CHECK | CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying, 'SYSTEM'::character varying])::text[]))) |
| story_messages_chat_id_fkey | FOREIGN KEY | FOREIGN KEY (chat_id) REFERENCES story_chats(id) ON DELETE CASCADE |
| story_messages_pkey | PRIMARY KEY | PRIMARY KEY (id) |
| fk_story_messages_reached_ending | FOREIGN KEY | FOREIGN KEY (reached_ending_id) REFERENCES story_endings(id) ON DELETE SET NULL |
| uq_story_messages_order | UNIQUE | UNIQUE (chat_id, message_order) |
| story_messages_credit_transaction_id_fkey | FOREIGN KEY | FOREIGN KEY (credit_transaction_id) REFERENCES credit_transactions(id) |
| story_messages_model_code_fkey | FOREIGN KEY | FOREIGN KEY (model_code) REFERENCES chat_models(code) |

## Indexes

| Name | Definition |
| ---- | ---------- |
| story_messages_pkey | CREATE UNIQUE INDEX story_messages_pkey ON public.story_messages USING btree (id) |
| uq_story_messages_order | CREATE UNIQUE INDEX uq_story_messages_order ON public.story_messages USING btree (chat_id, message_order) |
| idx_story_messages_admin_role_created_chat | CREATE INDEX idx_story_messages_admin_role_created_chat ON public.story_messages USING btree (role, created_at, chat_id) |
| idx_story_messages_admin_chat_role_created | CREATE INDEX idx_story_messages_admin_chat_role_created ON public.story_messages USING btree (chat_id, role, created_at) |
| uq_story_messages_credit_transaction | CREATE UNIQUE INDEX uq_story_messages_credit_transaction ON public.story_messages USING btree (credit_transaction_id) WHERE (credit_transaction_id IS NOT NULL) |

## Relations

```mermaid
erDiagram

"public.story_choices" }o--|| "public.story_messages" : "FOREIGN KEY (message_id) REFERENCES story_messages(id) ON DELETE CASCADE"
"public.story_message_versions" }o--|| "public.story_messages" : "FOREIGN KEY (message_id) REFERENCES story_messages(id) ON DELETE CASCADE"
"public.story_messages" }o--|| "public.story_chats" : "FOREIGN KEY (chat_id) REFERENCES story_chats(id) ON DELETE CASCADE"
"public.story_messages" }o--o| "public.story_endings" : "FOREIGN KEY (reached_ending_id) REFERENCES story_endings(id) ON DELETE SET NULL"
"public.story_messages" }o--o| "public.credit_transactions" : "FOREIGN KEY (credit_transaction_id) REFERENCES credit_transactions(id)"
"public.story_messages" }o--o| "public.chat_models" : "FOREIGN KEY (model_code) REFERENCES chat_models(code)"

"public.story_messages" {
  bigint id
  bigint chat_id FK
  varchar_16_ role
  text content
  integer message_order
  timestamp_with_time_zone created_at
  bigint reached_ending_id FK
  varchar_100_ reached_ending_name_snapshot
  bigint credit_transaction_id FK
  varchar_20_ model_code FK
}
"public.story_choices" {
  bigint id
  bigint chat_id FK
  bigint message_id FK
  text choice_text
  smallint choice_order
  boolean is_selected
  timestamp_with_time_zone selected_at
  timestamp_with_time_zone created_at
  boolean is_edited
}
"public.story_message_versions" {
  bigint id
  bigint message_id FK
  integer version_number
  text content
  text choices
  timestamp_with_time_zone created_at
  bigint credit_transaction_id FK
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
  uuid creation_id
  text story_prologue_snapshot
  varchar_100_ reached_ending_name_snapshot
  jsonb occurred_main_event_names_snapshot
  text persona_name_snapshot
  text persona_description_snapshot
  varchar_20_ model_code FK
  boolean long_response
}
"public.story_endings" {
  bigint id
  varchar_100_ title
  text content
  text condition_text
  smallint sort_order
  boolean enabled
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
  bigint start_setting_id FK
  varchar_100_ name
  integer min_turns
  text achievement_condition
  text epilogue
}
"public.credit_transactions" {
  bigint id
  bigint user_id FK
  bigint amount
  varchar_30_ reason
  varchar_30_ ref_type
  bigint ref_id
  varchar_255_ idempotency_key
  timestamp_with_time_zone created_at
}
"public.chat_models" {
  varchar_20_ code
  varchar_30_ name
  bigint turn_cost
  integer sort_order
  boolean is_active
  boolean is_default
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
}
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
