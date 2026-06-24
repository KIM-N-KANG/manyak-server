# 3계층 네트워크: VPC, 2 AZ × (public/app/db) 서브넷, IGW, NAT(MVP 단일),
# 계층별 route table, S3 Gateway Endpoint. 도면 docs/architecture-v1 기준.
# 가동 자원은 첫 AZ(2a)에 두고, 둘째 AZ(2c)는 ALB·RDS 의 2 AZ 요건용 예비 서브넷이다.

data "aws_region" "current" {}

locals {
  # 가용영역 접미사 (예: ap-northeast-2a -> 2a). 리소스 Name 태그용
  az_suffix = [for az in var.azs : substr(az, length(az) - 2, 2)]
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.project}-${var.environment}-vpc"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.project}-${var.environment}-igw"
  }
}

# --- 서브넷: 계층별 2 AZ ---
resource "aws_subnet" "public" {
  count = length(var.azs)

  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.azs[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.project}-${var.environment}-public-${local.az_suffix[count.index]}"
  }
}

resource "aws_subnet" "app" {
  count = length(var.azs)

  vpc_id            = aws_vpc.this.id
  cidr_block        = var.app_subnet_cidrs[count.index]
  availability_zone = var.azs[count.index]

  tags = {
    Name = "${var.project}-${var.environment}-app-${local.az_suffix[count.index]}"
  }
}

# db 계층은 Isolated(폐쇄망): 인터넷 라우트 없이 local 통신만 (route table 참고)
resource "aws_subnet" "db" {
  count = length(var.azs)

  vpc_id            = aws_vpc.this.id
  cidr_block        = var.db_subnet_cidrs[count.index]
  availability_zone = var.azs[count.index]

  tags = {
    Name = "${var.project}-${var.environment}-db-${local.az_suffix[count.index]}"
  }
}

# --- NAT Gateway (MVP: 단일, 첫 AZ public 에 배치) ---
resource "aws_eip" "nat" {
  count = var.single_nat_gateway ? 1 : length(var.azs)

  domain = "vpc"

  tags = {
    Name = "${var.project}-${var.environment}-nat-eip-${local.az_suffix[count.index]}"
  }
}

resource "aws_nat_gateway" "this" {
  count = var.single_nat_gateway ? 1 : length(var.azs)

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = {
    Name = "${var.project}-${var.environment}-nat-${local.az_suffix[count.index]}"
  }

  # IGW 가 먼저 attach 되어야 NAT 가 외부로 나갈 수 있다
  depends_on = [aws_internet_gateway.this]
}

# --- Route Table: 계층별 분리 (메인 RT 미사용, 각 서브넷에 명시적 연결) ---

# public: 0.0.0.0/0 -> IGW
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = {
    Name = "${var.project}-${var.environment}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  count = length(var.azs)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# app: 0.0.0.0/0 -> NAT. 단일 NAT 면 모든 app 이 nat[0] 을 경유(2c 는 교차-AZ)
resource "aws_route_table" "app" {
  count = length(var.azs)

  vpc_id = aws_vpc.this.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = var.single_nat_gateway ? aws_nat_gateway.this[0].id : aws_nat_gateway.this[count.index].id
  }

  tags = {
    Name = "${var.project}-${var.environment}-app-rt-${local.az_suffix[count.index]}"
  }
}

resource "aws_route_table_association" "app" {
  count = length(var.azs)

  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app[count.index].id
}

# db: 인터넷 라우트 없음 (Isolated/폐쇄망, local 통신만)
resource "aws_route_table" "db" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.project}-${var.environment}-db-rt"
  }
}

resource "aws_route_table_association" "db" {
  count = length(var.azs)

  subnet_id      = aws_subnet.db[count.index].id
  route_table_id = aws_route_table.db.id
}

# --- S3 Gateway Endpoint (무료): app RT 에 연결해 app->S3/ECR 레이어 트래픽을 NAT 우회 ---
resource "aws_vpc_endpoint" "s3" {
  count = var.enable_s3_gateway_endpoint ? 1 : 0

  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.app[*].id

  tags = {
    Name = "${var.project}-${var.environment}-s3-endpoint"
  }
}
