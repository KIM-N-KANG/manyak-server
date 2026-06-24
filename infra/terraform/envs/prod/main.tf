# 운영 환경 구성. 실제 인프라 모듈은 후속 서브태스크에서 추가한다.
#
#   #3 KNK-236 ECR        -> module "ecr"
#   #4 KNK-237 VPC        -> module "network"
#   #5 KNK-238 SG/IAM     -> module "security"
#   #6 KNK-239 RDS/Redis  -> module "data"
#   #7 KNK-240 EC2/ALB    -> module "compute", module "edge"
#
# 예:
# module "network" {
#   source      = "../../modules/network"
#   project     = var.project
#   environment = var.environment
# }
