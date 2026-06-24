# 데이터 계층: RDS PostgreSQL(단일 AZ, MVP)과 선택적 ElastiCache Redis.
#  - DB subnet group은 2 AZ db 서브넷을 포함한다(RDS 생성 요건 + Multi-AZ 전환 대비).
#  - 마스터 자격증명은 manage_master_user_password로 Secrets Manager가 자동 생성·관리한다(코드·state에 평문 미노출).
#  - SG는 security 모듈의 rds-sg/redis-sg를 연결한다(폐쇄망, app 계층에서만 접근).
# 주의: AWS로 전달되는 name·description은 ASCII만 사용한다(한글·'>' 등 금지 → apply 거부).

# ──────────────────────────── RDS PostgreSQL ────────────────────────────

resource "aws_db_subnet_group" "this" {
  name        = "${var.project}-${var.environment}-db-subnet-group"
  description = "DB subnet group across 2 AZ private db subnets"
  subnet_ids  = var.db_subnet_ids

  tags = {
    Name = "${var.project}-${var.environment}-db-subnet-group"
  }
}

resource "aws_db_parameter_group" "postgres" {
  name        = "${var.project}-${var.environment}-pg"
  family      = var.db_parameter_group_family
  description = "PostgreSQL parameter group for ${var.project}-${var.environment}"

  tags = {
    Name = "${var.project}-${var.environment}-pg"
  }
}

resource "aws_db_instance" "postgres" {
  identifier     = "${var.project}-${var.environment}-pg"
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  # 마스터 비밀번호는 Secrets Manager가 자동 생성·관리(코드/state에 평문 미노출). 앱 주입은 KNK-241.
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.rds_security_group_id]
  parameter_group_name   = aws_db_parameter_group.postgres.name

  # MVP: 단일 AZ(2a). Multi-AZ standby 전환은 후속(subnet group이 2 AZ라 가능).
  multi_az          = false
  availability_zone = var.db_availability_zone

  backup_retention_period = var.db_backup_retention_days
  publicly_accessible     = false
  deletion_protection     = var.db_deletion_protection
  skip_final_snapshot     = var.db_skip_final_snapshot
  # skip_final_snapshot=false(운영)면 삭제 시 최종 스냅샷 이름이 필요하다
  final_snapshot_identifier = var.db_skip_final_snapshot ? null : "${var.project}-${var.environment}-pg-final"
  apply_immediately         = false

  tags = {
    Name = "${var.project}-${var.environment}-pg"
  }
}

# ─────────────────────── ElastiCache Redis (선택) ───────────────────────

resource "aws_elasticache_subnet_group" "redis" {
  count = var.enable_redis ? 1 : 0

  name        = "${var.project}-${var.environment}-redis-subnet-group"
  description = "Redis subnet group across private db subnets"
  subnet_ids  = var.db_subnet_ids

  tags = {
    Name = "${var.project}-${var.environment}-redis-subnet-group"
  }
}

resource "aws_elasticache_cluster" "redis" {
  count = var.enable_redis ? 1 : 0

  cluster_id           = "${var.project}-${var.environment}-redis"
  engine               = "redis"
  engine_version       = var.redis_engine_version
  node_type            = var.redis_node_type
  num_cache_nodes      = 1
  port                 = 6379
  parameter_group_name = var.redis_parameter_group_name
  subnet_group_name    = aws_elasticache_subnet_group.redis[0].name
  security_group_ids   = [var.redis_security_group_id]

  tags = {
    Name = "${var.project}-${var.environment}-redis"
  }
}
