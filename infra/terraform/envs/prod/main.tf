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

  # KNK-294 CORS 허용 오리진 주입: apex + www(FE). 미주입 시 compose 기본값(apex만)으로 떨어져 www POST가 403이 된다.
  cors_allowed_origins = "https://manyak.app,https://www.manyak.app"

  # EC2 부팅(deploy.sh: ECR pull·시크릿 조회) 전에 준비되어야 하는 것들을 명시적으로 대기:
  # SG egress 규칙·IAM attachment(security), 시크릿 읽기 정책(ec2_secrets_read), 앱 시크릿 값(secrets 의 secret+version).
  depends_on = [module.security, module.secrets, aws_iam_role_policy.ec2_secrets_read]
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

# #3 KNK-236 GitHub Actions(OIDC) -> ECR 푸시 역할 (manyak-server 전용)
module "github_oidc" {
  source               = "../../modules/github-oidc"
  project              = var.project
  environment          = var.environment
  create_oidc_provider = var.create_github_oidc_provider
  # KNK-260: 최소권한 — server 워크플로는 manyak-server ECR 만 푸시한다.
  ecr_repository_arns = [module.ecr.repository_arns["manyak-server"]]
  # KNK-241: deploy 의 ssm:SendCommand 를 실제 운영 인스턴스로만 제한
  deploy_instance_ids = [module.compute.instance_id]
}

# #B KNK-260 manyak-ai 전용 배포 SSM 문서.
# AI 역할이 범용 AWS-RunShellScript(임의 셸 실행)가 아니라 이 고정 문서만 SendCommand 하도록 제한한다(codex P2#2).
# 문서 내용은 ai 이미지 1개를 받아 deploy.sh 를 ai 오버라이드로 실행하는 것뿐이고, ImageUri 는 manyak-ai ECR
# 경로 패턴으로 제약해 주입을 차단한다(허용 문자에 셸 메타문자·공백 없음).
resource "aws_ssm_document" "ai_deploy" {
  name            = "${var.project}-${var.environment}-ai-deploy"
  document_type   = "Command"
  document_format = "YAML"

  content = yamlencode({
    schemaVersion = "2.2"
    description   = "Deploy manyak-ai by running deploy.sh with a pinned AI image."
    parameters = {
      ImageUri = {
        type           = "String"
        description    = "manyak-ai ECR image URI (<acct>.dkr.ecr.<region>.amazonaws.com/manyak-ai:<tag>)"
        allowedPattern = "^[0-9]+\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com/manyak-ai:[A-Za-z0-9._-]+$"
      }
    }
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "deployAi"
      inputs = {
        runCommand = ["AI_IMAGE_OVERRIDE='{{ImageUri}}' bash /opt/manyak/deploy.sh"]
      }
    }]
  })

  tags = {
    Name = "${var.project}-${var.environment}-ai-deploy"
  }
}

# #B KNK-260 manyak-ai 전용 OIDC 역할.
# CI 역할을 레포별로 분리해 최소권한·감사를 확보한다(server 역할과 권한이 섞이지 않음).
# manyak-ai 레포 main 만 신뢰하며, 권한은 manyak-ai ECR 푸시 + 위 전용 SSM 문서로만 배포(임의 셸 불가).
module "github_oidc_ai" {
  source      = "../../modules/github-oidc"
  project     = var.project
  environment = var.environment
  # OIDC provider 는 위 github_oidc 가 생성하므로 여기선 재생성하지 않고 조회해서 쓴다.
  create_oidc_provider = false
  github_repo          = "manyak-ai"
  role_name            = "${var.project}-${var.environment}-gha-ai"
  ecr_repository_arns  = [module.ecr.repository_arns["manyak-ai"]]
  deploy_instance_ids  = [module.compute.instance_id]
  # codex P2#2: 범용 RunShellScript 대신 전용 문서로만 SendCommand 허용(임의 셸 차단).
  ssm_document_arns = [aws_ssm_document.ai_deploy.arn]

  # provider data 조회가 github_oidc 의 provider 생성 이후 일어나도록 보장(최초 apply 순서).
  depends_on = [module.github_oidc]
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
