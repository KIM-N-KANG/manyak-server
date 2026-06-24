# 운영 환경 구성. 후속 서브태스크에서 컴퓨트 모듈을 추가한다.
#
#   #7 KNK-240 EC2/ALB    -> module "compute", module "edge"

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

# #3 KNK-236 컨테이너 이미지 레포지토리 (main 머지 시 CI 가 prod 이미지 푸시)
module "ecr" {
  source      = "../../modules/ecr"
  project     = var.project
  environment = var.environment
}

# #3 KNK-236 GitHub Actions(OIDC) -> ECR 푸시 역할
module "github_oidc" {
  source               = "../../modules/github-oidc"
  project              = var.project
  environment          = var.environment
  create_oidc_provider = var.create_github_oidc_provider
  ecr_repository_arns  = values(module.ecr.repository_arns)
}
