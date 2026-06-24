# 운영 환경 구성. 후속 서브태스크에서 네트워크·보안·데이터·컴퓨트 모듈을 추가한다.
#
#   #4 KNK-237 VPC        -> module "network"
#   #5 KNK-238 SG/IAM     -> module "security"
#   #6 KNK-239 RDS/Redis  -> module "data"
#   #7 KNK-240 EC2/ALB    -> module "compute", module "edge"

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
