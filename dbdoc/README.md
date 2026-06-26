# manyak

## Tables

| Name | Columns | Comment | Type |
| ---- | ------- | ------- | ---- |
| [public.story_creation_tags](public.story_creation_tags.md) | 8 |  | BASE TABLE |
| [public.story_creation_sessions](public.story_creation_sessions.md) | 6 |  | BASE TABLE |
| [public.story_creation_session_tags](public.story_creation_session_tags.md) | 4 |  | BASE TABLE |
| [public.story_creation_examples](public.story_creation_examples.md) | 6 |  | BASE TABLE |
| [public.story_creation_example_recommended_infos](public.story_creation_example_recommended_infos.md) | 5 |  | BASE TABLE |
| [public.stories](public.stories.md) | 10 |  | BASE TABLE |
| [public.story_settings](public.story_settings.md) | 8 |  | BASE TABLE |
| [public.story_start_settings](public.story_start_settings.md) | 7 |  | BASE TABLE |
| [public.story_suggested_inputs](public.story_suggested_inputs.md) | 5 |  | BASE TABLE |
| [public.story_play_sessions](public.story_play_sessions.md) | 12 |  | BASE TABLE |
| [public.story_messages](public.story_messages.md) | 6 |  | BASE TABLE |
| [public.story_choices](public.story_choices.md) | 8 |  | BASE TABLE |
| [public.story_creation_example_ratings](public.story_creation_example_ratings.md) | 5 |  | BASE TABLE |
| [public.feedbacks](public.feedbacks.md) | 7 |  | BASE TABLE |
| [public.ai_call_logs](public.ai_call_logs.md) | 22 |  | BASE TABLE |
| [public.users](public.users.md) | 9 |  | BASE TABLE |
| [public.social_accounts](public.social_accounts.md) | 9 |  | BASE TABLE |

## Relations

```mermaid
erDiagram

"public.story_creation_session_tags" }o--|| "public.story_creation_tags" : "FOREIGN KEY (tag_id) REFERENCES story_creation_tags(id)"
"public.story_creation_session_tags" }o--|| "public.story_creation_sessions" : "FOREIGN KEY (creation_session_id) REFERENCES story_creation_sessions(id) ON DELETE CASCADE"
"public.story_creation_examples" }o--|| "public.story_creation_sessions" : "FOREIGN KEY (creation_session_id) REFERENCES story_creation_sessions(id) ON DELETE CASCADE"
"public.story_creation_example_recommended_infos" }o--|| "public.story_creation_examples" : "FOREIGN KEY (example_id) REFERENCES story_creation_examples(id) ON DELETE CASCADE"
"public.story_settings" |o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_start_settings" |o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_suggested_inputs" }o--|| "public.story_start_settings" : "FOREIGN KEY (start_setting_id) REFERENCES story_start_settings(id) ON DELETE CASCADE"
"public.story_play_sessions" }o--|| "public.stories" : "FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE"
"public.story_play_sessions" }o--o| "public.story_start_settings" : "FOREIGN KEY (start_setting_id) REFERENCES story_start_settings(id) ON DELETE SET NULL"
"public.story_messages" }o--|| "public.story_play_sessions" : "FOREIGN KEY (play_session_id) REFERENCES story_play_sessions(id) ON DELETE CASCADE"
"public.story_choices" }o--|| "public.story_play_sessions" : "FOREIGN KEY (play_session_id) REFERENCES story_play_sessions(id) ON DELETE CASCADE"
"public.story_choices" }o--|| "public.story_messages" : "FOREIGN KEY (message_id) REFERENCES story_messages(id) ON DELETE CASCADE"
"public.story_creation_example_ratings" |o--|| "public.story_creation_examples" : "FOREIGN KEY (example_id) REFERENCES story_creation_examples(id) ON DELETE CASCADE"
"public.social_accounts" }o--|| "public.users" : "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"

"public.story_creation_tags" {
  bigint id
  varchar_50_ tag_type
  varchar_30_ name
  varchar_20_ tag_source
  integer sort_order
  boolean is_active
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
}
"public.story_creation_sessions" {
  bigint id
  bigint user_id
  bigint story_id
  varchar_30_ status
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
}
"public.story_creation_session_tags" {
  bigint id
  bigint creation_session_id FK
  bigint tag_id FK
  timestamp_with_time_zone created_at
}
"public.story_creation_examples" {
  bigint id
  bigint creation_session_id FK
  text example_text
  smallint example_order
  boolean is_selected
  timestamp_with_time_zone created_at
}
"public.story_creation_example_recommended_infos" {
  bigint id
  bigint example_id FK
  text info_text
  smallint info_order
  timestamp_with_time_zone created_at
}
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
}
"public.story_suggested_inputs" {
  bigint id
  bigint start_setting_id FK
  text input_text
  smallint input_order
  timestamp_with_time_zone created_at
}
"public.story_play_sessions" {
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
}
"public.story_messages" {
  bigint id
  bigint play_session_id FK
  varchar_16_ role
  text content
  integer message_order
  timestamp_with_time_zone created_at
}
"public.story_choices" {
  bigint id
  bigint play_session_id FK
  bigint message_id FK
  text choice_text
  smallint choice_order
  boolean is_selected
  timestamp_with_time_zone selected_at
  timestamp_with_time_zone created_at
}
"public.story_creation_example_ratings" {
  bigint id
  bigint example_id FK
  varchar_8_ rating
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
}
"public.feedbacks" {
  bigint id
  bigint user_id
  text body
  varchar_320_ email
  varchar_20_ platform
  varchar_50_ app_version
  timestamp_with_time_zone created_at
}
"public.ai_call_logs" {
  bigint id
  varchar_128_ request_id
  varchar_50_ caller_service
  varchar_40_ feature
  varchar_64_ anonymous_id_hash
  varchar_128_ session_id
  bigint story_id
  uuid chat_id
  integer turn_index
  varchar_40_ provider
  varchar_100_ model
  varchar_40_ prompt_template_version
  varchar_16_ status
  bigint latency_ms
  integer input_token_count
  integer output_token_count
  integer retry_count
  varchar_100_ error_code
  varchar_64_ sentry_event_id
  timestamp_with_time_zone created_at
  timestamp_with_time_zone completed_at
  jsonb prompt_versions
}
"public.users" {
  bigint id
  uuid public_id
  varchar_50_ nickname
  text profile_image_url
  text profile_thumbnail_base64
  varchar_20_ status
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
  timestamp_with_time_zone deleted_at
}
"public.social_accounts" {
  bigint id
  bigint user_id FK
  varchar_20_ provider
  varchar_255_ provider_user_id
  varchar_255_ email
  timestamp_with_time_zone connected_at
  timestamp_with_time_zone last_login_at
  timestamp_with_time_zone created_at
  timestamp_with_time_zone updated_at
}
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
