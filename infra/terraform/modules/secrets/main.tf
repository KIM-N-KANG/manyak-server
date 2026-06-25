# 앱 런타임 시크릿(Sentry DSN·Slack webhook 등)을 Secrets Manager에 보관한다.
# DB 마스터 자격증명은 RDS가 자동 관리(data 모듈의 manage_master_user_password)하므로 여기엔 포함하지 않는다.
# 실제 값은 콘솔 또는 `aws secretsmanager put-secret-value`로 입력하고, terraform은 그릇(빈 JSON)만 만든다.

resource "aws_secretsmanager_secret" "app" {
  # 슬래시 경로 네이밍(ASCII). DB secret(rds!...)과 구분되는 앱 전용 시크릿.
  name        = "${var.project}/${var.environment}/app"
  description = "manyak app runtime secrets (Sentry DSN, Slack webhook; AI/LLM key added with ai service)"

  tags = {
    Name = "${var.project}-${var.environment}-app-secrets"
  }
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id

  # 초기 빈 그릇. 실제 값은 콘솔/CLI로 입력한다(아래 ignore_changes 로 terraform 이 덮어쓰지 않음).
  # 앱은 이 키들을 환경변수로 바인딩한다(application.yml 참조).
  secret_string = jsonencode({
    SENTRY_DSN                        = ""
    MANYAK_SLACK_FEEDBACK_WEBHOOK_URL = ""
  })

  lifecycle {
    # 콘솔/CLI로 입력한 실제 값을 terraform 이 빈 값으로 되돌리지 않도록 무시한다.
    ignore_changes = [secret_string]
  }
}
