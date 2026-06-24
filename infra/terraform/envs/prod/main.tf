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

  # SG egress 규칙·IAM 정책 attachment가 EC2 부팅(user-data의 ECR pull·아웃바운드) 전에 준비되도록
  # security 모듈 전체 완료를 기다린다. (app_sg/instance_profile 객체 참조만으론 규칙·attachment 순서가 보장되지 않음)
  depends_on = [module.security]
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
}
