# 운영 환경 구성. 컴퓨트·엣지까지 구성됨. KNK-241(시크릿 주입·배포·스모크)에서 마무리.

# #4 KNK-237 3계층 네트워크 (VPC·서브넷·IGW·NAT·라우팅·S3 Gateway Endpoint)
module "network" {
  source      = "../../modules/network"
  project     = var.project
  environment = var.environment
}

# #5 KNK-238 보안 그룹 체인 + EC2 IAM (SSM·ECR pull·CloudWatch, SSH 미개방)
module "security" {
  source              = "../../modules/security"
  project             = var.project
  environment         = var.environment
  vpc_id              = module.network.vpc_id
  ecr_repository_arns = values(module.ecr.repository_arns)
}

# #6 KNK-239 데이터 계층 (RDS PostgreSQL + ElastiCache Redis)
module "data" {
  source                  = "../../modules/data"
  project                 = var.project
  environment             = var.environment
  db_subnet_ids           = module.network.db_subnet_ids
  rds_security_group_id   = module.security.rds_security_group_id
  redis_security_group_id = module.security.redis_security_group_id
  db_availability_zone    = element(module.network.availability_zones, 0)
  enable_redis            = var.enable_redis
}

# #7 KNK-240 컴퓨트 (EC2 + docker compose: server + ai)
module "compute" {
  source                = "../../modules/compute"
  project               = var.project
  environment           = var.environment
  app_subnet_id         = module.network.app_subnet_ids[0]
  app_security_group_id = module.security.app_security_group_id
  instance_profile_name = module.security.instance_profile_name
  aws_region            = var.region
  ecr_registry_url      = split("/", module.ecr.repository_urls["manyak-server"])[0]
  server_image          = "${module.ecr.repository_urls["manyak-server"]}:latest"
  ai_image              = "${module.ecr.repository_urls["manyak-ai"]}:latest"
  compose_content       = file("${path.module}/../../../../docker-compose.prod.yml")

  # KNK-241 시크릿 주입: user-data가 부팅 시 Secrets Manager에서 읽어 .env 생성
  db_secret_arn  = module.data.db_master_user_secret_arn
  app_secret_arn = module.secrets.app_secret_arn
  db_address     = module.data.db_address
  db_port        = module.data.db_port
  db_name        = module.data.db_name

  # SG egress 규칙·IAM 정책 attachment(+시크릿 읽기 정책)가 EC2 부팅(user-data의 ECR pull·시크릿 조회) 전에 준비되도록 대기
  depends_on = [module.security, aws_iam_role_policy.ec2_secrets_read]
}

# #7 KNK-240 엣지 (ALB + ACM + Cloudflare DNS)
module "edge" {
  source                = "../../modules/edge"
  project               = var.project
  environment           = var.environment
  vpc_id                = module.network.vpc_id
  public_subnet_ids     = module.network.public_subnet_ids
  alb_security_group_id = module.security.alb_security_group_id
  ec2_instance_id       = module.compute.instance_id
}

# #3 KNK-236 컨테이너 이미지 레포지토리 (main 머지 시 CI 가 prod 이미지 푸시)
module "ecr" {
  source           = "../../modules/ecr"
  project          = var.project
  environment      = var.environment
  repository_names = ["manyak-server", "manyak-ai"] # KNK-240: ai 이미지 레포 추가
}

# #3 KNK-236 GitHub Actions(OIDC) -> ECR 푸시 역할
module "github_oidc" {
  source               = "../../modules/github-oidc"
  project              = var.project
  environment          = var.environment
  create_oidc_provider = var.create_github_oidc_provider
  ecr_repository_arns  = values(module.ecr.repository_arns)
  # KNK-241: deploy 의 ssm:SendCommand 를 실제 운영 인스턴스로만 제한
  deploy_instance_ids = [module.compute.instance_id]
}

# #8 KNK-241 앱 런타임 시크릿 (Sentry DSN·Slack webhook 등; DB 비번은 RDS가 자동 관리)
module "secrets" {
  source      = "../../modules/secrets"
  project     = var.project
  environment = var.environment
}

# #8 KNK-241 EC2 role 시크릿 읽기 권한.
# security↔data 순환을 피하려 security 모듈 안이 아닌 여기(envs)에서 role 에 attach 한다.
data "aws_iam_policy_document" "ec2_secrets_read" {
  statement {
    sid       = "SecretsRead"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [module.data.db_master_user_secret_arn, module.secrets.app_secret_arn]
  }
}

resource "aws_iam_role_policy" "ec2_secrets_read" {
  name   = "secrets-read"
  role   = module.security.ec2_role_name
  policy = data.aws_iam_policy_document.ec2_secrets_read.json
}
