# 단일 EC2(2a private)에서 docker compose로 server+ai를 구동한다.
# 접속은 SSM(인스턴스 프로파일), 이미지 pull은 ECR. SSH(22)는 열지 않는다.

# 표준 AL2023 최신 AMI를 SSM 공개 파라미터로 받는다.
# (글로브 필터는 minimal AMI까지 매칭하고 most_recent가 비결정적이라, minimal이 잡히면
#  user-data의 aws CLI(ECR 로그인)가 없어 기동에 실패한다. SSM 파라미터는 표준 AMI를 고정.)
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "app" {
  ami                         = data.aws_ssm_parameter.al2023.value
  instance_type               = var.instance_type
  subnet_id                   = var.app_subnet_id
  vpc_security_group_ids      = [var.app_security_group_id]
  iam_instance_profile        = var.instance_profile_name
  associate_public_ip_address = false

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    aws_region       = var.aws_region
    ecr_registry_url = var.ecr_registry_url
    server_image     = var.server_image
    ai_image         = var.ai_image
    compose_content  = var.compose_content
    environment      = var.environment
    db_secret_arn    = var.db_secret_arn
    app_secret_arn   = var.app_secret_arn
    db_address       = var.db_address
    db_port          = var.db_port
    db_name          = var.db_name
  })
  # user-data가 바뀌면 인스턴스를 교체해 재프로비저닝
  user_data_replace_on_change = true

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required" # IMDSv2 강제
  }

  lifecycle {
    # SSM(latest) 파라미터는 새 AMI 발행 시 값이 바뀌지만, 그때마다 인스턴스를 교체하지 않도록
    # ami 변경은 무시한다(최초 생성 시점의 표준 AMI로 고정). 의도적 AMI 갱신은 taint/-replace 로.
    ignore_changes = [ami]
  }

  tags = {
    Name = "${var.project}-${var.environment}-app"
  }
}
