# 보안 그룹 체인(alb→app→rds/redis)과 EC2 인스턴스 IAM 역할.
# 핵심 원칙(멘토 권장):
#  - 접근 제어는 IP가 아닌 "SG 참조"로 체인을 구성한다.
#  - EC2 접근은 SSH(22) 대신 SSM Session Manager를 사용한다(22 미개방).
#  - IAM은 최소 권한. 자격증명 키 대신 인스턴스 프로파일(임시 자격증명)을 쓴다.

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

# ──────────────────────────── Security Groups ────────────────────────────
# Terraform은 aws_security_group에 egress를 지정하지 않으면 AWS 기본 allow-all
# egress 규칙을 제거한다. 따라서 egress 규칙을 따로 만들지 않은 SG는 아웃바운드가
# 없는 상태가 된다(SG는 stateful이라 수신 트래픽의 응답은 egress 규칙 없이도 나간다).

resource "aws_security_group" "alb" {
  name        = "${var.project}-${var.environment}-alb-sg"
  description = "ALB: HTTP/HTTPS from internet, 8080 out to app SG"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project}-${var.environment}-alb-sg"
  }
}

resource "aws_security_group" "app" {
  name        = "${var.project}-${var.environment}-app-sg"
  description = "App (EC2): 8080 from ALB SG, all outbound"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project}-${var.environment}-app-sg"
  }
}

resource "aws_security_group" "rds" {
  name        = "${var.project}-${var.environment}-rds-sg"
  description = "RDS PostgreSQL: 5432 from app SG (isolated, no egress)"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project}-${var.environment}-rds-sg"
  }
}

resource "aws_security_group" "redis" {
  name        = "${var.project}-${var.environment}-redis-sg"
  description = "ElastiCache Redis: 6379 from app SG (isolated, no egress)"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project}-${var.environment}-redis-sg"
  }
}

# ──────────────────── SG 체인 규칙 (IP 아닌 SG 참조) ────────────────────

# ALB ← 인터넷 80/443
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from internet (for HTTPS redirect)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from internet"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# ALB → App 8080 (대상 그룹 전달)
resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "ALB -> App 8080"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# App ← ALB 8080
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "App 8080 from ALB"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# App → 아웃바운드 전체.
# 외부 LLM API(엔드포인트·포트가 다양)·ECR pull·SSM/CloudWatch 엔드포인트·DB/Redis 도달이
# 모두 필요해 전면 허용한다. 데이터 유출 경로를 더 좁히려면 443(HTTPS)·53(DNS) 등으로
# 제한할 수 있으나, 외부 의존성 포트를 MVP 단계에서 단정하기 어려워 전체 허용으로 둔다.
resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  description       = "App outbound all (ECR/LLM/DB/SSM/CloudWatch)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# RDS ← App 5432
resource "aws_vpc_security_group_ingress_rule" "rds_from_app" {
  security_group_id            = aws_security_group.rds.id
  description                  = "RDS 5432 from App"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# Redis ← App 6379
resource "aws_vpc_security_group_ingress_rule" "redis_from_app" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis 6379 from App"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

# rds/redis는 egress 규칙을 만들지 않는다(위 SG 블록 주석 참고).
# Terraform이 기본 allow-all egress를 제거하므로 아웃바운드 없음 = 폐쇄망이 되고,
# stateful 특성상 App에서 들어온 5432/6379 요청의 응답은 자동으로 나간다.

# ──────────────────────── EC2 인스턴스 IAM 역할 ────────────────────────

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${var.project}-${var.environment}-ec2-role"
  description        = "EC2 instance role: SSM access, ECR pull, CloudWatch Logs"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json

  tags = {
    Name = "${var.project}-${var.environment}-ec2-role"
  }
}

# SSM Session Manager (SSH 22 대신 접속). 표준 관리형 정책.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# ECR 이미지 pull (인라인 최소권한): 인증 토큰은 전역, 레이어 read는 지정 레포로 제한.
# github-oidc 모듈의 ECR 정책과 동일한 per-repo 스코핑 컨벤션을 따른다.
data "aws_iam_policy_document" "ecr_pull" {
  statement {
    sid       = "EcrAuthToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPull"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = var.ecr_repository_arns
  }
}

resource "aws_iam_role_policy" "ecr_pull" {
  name   = "ecr-pull"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.ecr_pull.json
}

# CloudWatch Logs 전송 (인라인). 현재 리전·계정으로 제한.
# 로그 그룹 이름은 KNK-240(관측)에서 확정되며, 그때 log-group prefix로 더 좁힌다.
data "aws_iam_policy_document" "cw_logs" {
  statement {
    sid    = "CloudWatchLogsWrite"
    effect = "Allow"
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]
    resources = ["arn:aws:logs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:log-group:*"]
  }
}

resource "aws_iam_role_policy" "cw_logs" {
  name   = "cloudwatch-logs"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.cw_logs.json
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project}-${var.environment}-ec2-profile"
  role = aws_iam_role.ec2.name

  tags = {
    Name = "${var.project}-${var.environment}-ec2-profile"
  }
}
