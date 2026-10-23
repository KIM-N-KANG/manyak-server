-- 활성 AI 응답의 청구 모델만 기록한다. 기존 행은 추정하거나 백필하지 않는다.
ALTER TABLE story_messages ADD COLUMN model_code varchar(20) REFERENCES chat_models(code);
