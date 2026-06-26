variable "project" {
  description = "프로젝트 식별자 (태그·리소스 접두)"
  type        = string
}

variable "environment" {
  description = "배포 환경 (태그·리소스 접두)"
  type        = string
}

variable "app_subnet_id" {
  description = "EC2를 둘 private app 서브넷 ID (network app_subnet_ids[0] = 2a)"
  type        = string
}

variable "app_security_group_id" {
  description = "EC2에 연결할 app 보안 그룹 ID (security app_security_group_id)"
  type        = string
}

variable "instance_profile_name" {
  description = "EC2에 부착할 인스턴스 프로파일 이름 (security instance_profile_name)"
  type        = string
}

variable "instance_type" {
  description = "EC2 인스턴스 타입 (MVP)"
  type        = string
  default     = "t3.small"
}

variable "aws_region" {
  description = "ECR 로그인에 사용할 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "ecr_registry_url" {
  description = "ECR 레지스트리 호스트 (account.dkr.ecr.region.amazonaws.com). user-data의 docker login 대상"
  type        = string
}

variable "server_image" {
  description = "manyak-server 컨테이너 이미지 (ECR URL:tag)"
  type        = string
}

variable "ai_image" {
  description = "manyak-ai 컨테이너 이미지 (ECR URL:tag). 이미지 미준비 시 apply 후 compose pull 단계에서 대기"
  type        = string
}

variable "compose_content" {
  description = "docker-compose.prod.yml 원문 (env에서 file()로 읽어 주입). user-data가 인스턴스에 기록"
  type        = string
}

# KNK-241 시크릿 주입: user-data가 부팅 시 Secrets Manager에서 읽어 .env 생성
variable "db_secret_arn" {
  description = "RDS 마스터 자격증명 Secrets Manager ARN (data db_master_user_secret_arn). username/password 주입"
  type        = string
}

variable "app_secret_arn" {
  description = "앱 시크릿 Secrets Manager ARN (secrets app_secret_arn). Sentry DSN·Slack webhook 등 주입"
  type        = string
}

variable "db_address" {
  description = "RDS 호스트 주소 (data db_address). MANYAK_DB_URL 구성에 사용"
  type        = string
}

variable "db_port" {
  description = "RDS 포트 (data db_port)"
  type        = number
}

variable "db_name" {
  description = "초기 DB 이름 (data db_name)"
  type        = string
}

# KNK-294 CORS 허용 오리진: user-data가 .env에 MANYAK_CORS_ALLOWED_ORIGINS로 emit(콤마 구분 origin 목록).
# 미주입 시 compose 기본값(apex)으로 떨어져 FE(www 등)의 POST가 CORS 403으로 막힌다.
variable "cors_allowed_origins" {
  description = "CORS 허용 오리진(콤마 구분). MANYAK_CORS_ALLOWED_ORIGINS로 주입"
  type        = string
  default     = "https://manyak.app"

# KNK-284 Redis 주입: user-data가 .env에 SPRING_DATA_REDIS_HOST/PORT로 emit (refresh 토큰 저장)
variable "redis_address" {
  description = "ElastiCache Redis 엔드포인트 주소 (data redis_endpoint). SPRING_DATA_REDIS_HOST로 주입. enable_redis=false면 빈 문자열"
  type        = string
  default     = ""
}

variable "redis_port" {
  description = "ElastiCache Redis 포트 (data redis_port). SPRING_DATA_REDIS_PORT로 주입"
  type        = number
  default     = 6379
}
