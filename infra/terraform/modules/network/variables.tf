variable "project" {
  description = "프로젝트 식별자 (태그·리소스 접두)"
  type        = string
}

variable "environment" {
  description = "배포 환경 (태그·리소스 접두)"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록. 생성 후 축소가 불가하므로 넉넉히 /16 으로 둔다"
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "서브넷을 배치할 가용영역 2개 (ALB·RDS 의 2 AZ 요건). 인덱스 0=주 가동, 1=예비"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]

  validation {
    condition     = length(var.azs) == 2
    error_message = "azs 는 2개여야 합니다 (ALB·RDS 가 2 AZ 서브넷을 요구)."
  }
}

variable "public_subnet_cidrs" {
  description = "AZ 순서대로 매핑되는 public 서브넷 CIDR"
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "app_subnet_cidrs" {
  description = "AZ 순서대로 매핑되는 private app 서브넷 CIDR"
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.11.0/24"]
}

variable "db_subnet_cidrs" {
  description = "AZ 순서대로 매핑되는 private db 서브넷 CIDR (Isolated/폐쇄망)"
  type        = list(string)
  default     = ["10.0.20.0/24", "10.0.21.0/24"]
}

variable "single_nat_gateway" {
  description = "true 면 NAT GW 를 1개(첫 AZ)만 생성한다(MVP 비용절감). app 의 다른 AZ 는 교차-AZ 로 이 NAT 를 경유한다. AZ 장애 시 동반 다운·Cross-AZ 전송료 trade-off 가 있으며, HA 가 필요하면 false 로 두어 AZ 별로 생성한다."
  type        = bool
  default     = true
}

variable "enable_s3_gateway_endpoint" {
  description = "S3 Gateway VPC Endpoint 생성 여부(무료). app route table 에 연결해 S3·ECR 이미지 레이어 트래픽을 NAT 우회시킨다"
  type        = bool
  default     = true
}
