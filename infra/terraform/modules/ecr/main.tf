# 컨테이너 이미지 레포지토리.
# main 머지 시 GitHub Actions가 빌드한 prod 이미지를 푸시하고, EC2가 인스턴스 역할로 pull 한다.
resource "aws_ecr_repository" "this" {
  for_each = toset(var.repository_names)

  name                 = each.value
  image_tag_mutability = var.image_tag_mutability
  force_delete         = var.force_delete

  image_scanning_configuration {
    scan_on_push = var.scan_on_push
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name = each.value
  }
}

# 미사용 이미지 정리: 태그 없는 이미지 만료 후, 태그 이미지는 최신 N개만 유지한다.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "태그 없는 이미지 ${var.untagged_expire_days}일 후 만료"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.untagged_expire_days
        }
        action = {
          type = "expire"
        }
      },
      {
        rulePriority = 2
        description  = "태그 이미지는 최신 ${var.max_image_count}개만 유지"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.max_image_count
        }
        action = {
          type = "expire"
        }
      },
    ]
  })
}
