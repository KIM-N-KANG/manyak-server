variable "project" {
  description = "프로젝트 식별자 (태그·리소스 접두)"
  type        = string
}

variable "environment" {
  description = "배포 환경 (태그·리소스 접두)"
  type        = string
}

variable "db_subnet_ids" {
  description = "RDS·Redis subnet group에 쓸 private db 서브넷 ID 목록 (network db_subnet_ids, 2 AZ)"
  type        = list(string)

  validation {
    condition     = length(var.db_subnet_ids) >= 2
    error_message = "db_subnet_ids 는 2개 이상이어야 합니다 (RDS·ElastiCache 가 2 AZ subnet group 을 요구)."
  }
}

variable "rds_security_group_id" {
  description = "RDS에 연결할 보안 그룹 ID (security rds_security_group_id)"
  type        = string
}

variable "redis_security_group_id" {
  description = "Redis에 연결할 보안 그룹 ID (security redis_security_group_id)"
  type        = string
}

# --- RDS PostgreSQL ---

variable "db_engine_version" {
  description = "RDS PostgreSQL 엔진 버전"
  type        = string
  default     = "16.4"
}

variable "db_parameter_group_family" {
  description = "RDS 파라미터 그룹 family (engine major 버전에 맞춤)"
  type        = string
  default     = "postgres16"
}

variable "db_instance_class" {
  description = "RDS 인스턴스 클래스 (MVP)"
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage" {
  description = "RDS 할당 스토리지(GB)"
  type        = number
  default     = 20
}

variable "db_name" {
  description = "초기 데이터베이스 이름"
  type        = string
  default     = "manyak"
}

variable "db_username" {
  description = "마스터 사용자 이름 (비밀번호는 Secrets Manager가 자동 관리)"
  type        = string
  default     = "manyak"
}

variable "db_availability_zone" {
  description = "RDS 인스턴스를 둘 단일 AZ (MVP, 2a)"
  type        = string
  default     = "ap-northeast-2a"
}

variable "db_backup_retention_days" {
  description = "자동 백업 보관 일수"
  type        = number
  default     = 7
}

variable "db_skip_final_snapshot" {
  description = "삭제 시 최종 스냅샷 생략 (MVP true; 운영 데이터를 보호하려면 false)"
  type        = bool
  default     = true
}

variable "db_deletion_protection" {
  description = "삭제 보호 (MVP false)"
  type        = bool
  default     = false
}

# --- ElastiCache Redis (선택) ---

variable "enable_redis" {
  description = "ElastiCache Redis 생성 여부 (선택 토글)"
  type        = bool
  default     = true
}

variable "redis_node_type" {
  description = "Redis 노드 타입 (MVP)"
  type        = string
  default     = "cache.t3.micro"
}

variable "redis_engine_version" {
  description = "Redis 엔진 버전"
  type        = string
  default     = "7.1"
}

variable "redis_parameter_group_name" {
  description = "Redis 파라미터 그룹 이름 (엔진 major 에 맞춘 기본 그룹)"
  type        = string
  default     = "default.redis7"
}
