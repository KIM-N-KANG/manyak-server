# GitHub Actions가 장기 키 없이 AWS에 인증하도록 OIDC 신뢰를 구성한다.
# main 브랜치 워크플로만 이 역할을 assume 해 ECR 에 푸시할 수 있다.

# OIDC provider 의 thumbprint 를 런타임에 취득 (하드코딩 회피)
data "tls_certificate" "github" {
  count = var.create_oidc_provider ? 1 : 0
  url   = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github[0].certificates[0].sha1_fingerprint]

  tags = {
    Name = "github-actions-oidc"
  }
}

# 생성하지 않는 경우, 계정에 이미 있는 provider 를 조회해 ARN 을 얻는다
data "aws_iam_openid_connect_provider" "existing" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.existing[0].arn
  role_name         = coalesce(var.role_name, "${var.project}-${var.environment}-gha-ecr-push")
  subjects          = [for branch in var.allowed_branches : "repo:${var.github_owner}/${var.github_repo}:ref:refs/heads/${branch}"]
}

# OIDC 토큰으로만 assume, 지정 레포+브랜치(sub)로 제한
data "aws_iam_policy_document" "assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.subjects
    }
  }
}

resource "aws_iam_role" "ci" {
  name               = local.role_name
  description        = "GitHub Actions(OIDC)가 ECR 푸시를 위해 assume 하는 역할"
  assume_role_policy = data.aws_iam_policy_document.assume.json

  tags = {
    Name = local.role_name
  }
}

# 최소 권한: 토큰 발급(전역) + 지정 레포로의 푸시/풀
data "aws_iam_policy_document" "ecr_push" {
  statement {
    sid       = "EcrAuthToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPushPull"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = var.ecr_repository_arns
  }
}

resource "aws_iam_role_policy" "ecr_push" {
  name   = "ecr-push"
  role   = aws_iam_role.ci.id
  policy = data.aws_iam_policy_document.ecr_push.json
}
