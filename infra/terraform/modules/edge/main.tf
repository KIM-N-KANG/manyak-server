# ALB(2 AZ public) + ACM(HTTPS 종단) + Cloudflare DNS(api.manyak.app).
# TLS는 ALB가 ACM으로 종단하고, 백엔드 레그는 HTTP:8080(평문). 80은 443으로 redirect.
# Cloudflare가 manyak.app DNS를 관리하므로 ACM 검증 레코드와 api 레코드를 Cloudflare에 생성한다.
# 주의: AWS로 가는 name/description은 ASCII만 사용.

data "cloudflare_zone" "this" {
  name = var.cloudflare_zone_name
}

# ──────────────────────────── ALB ────────────────────────────

resource "aws_lb" "this" {
  name               = "${var.project}-${var.environment}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnet_ids

  tags = {
    Name = "${var.project}-${var.environment}-alb"
  }
}

resource "aws_lb_target_group" "app" {
  name        = "${var.project}-${var.environment}-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "instance"

  health_check {
    enabled             = true
    path                = "/actuator/health"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.project}-${var.environment}-tg"
  }
}

resource "aws_lb_target_group_attachment" "app" {
  target_group_arn = aws_lb_target_group.app.arn
  target_id        = var.ec2_instance_id
  port             = 8080
}

# ──────────────────── ACM (DNS 검증, Cloudflare) ────────────────────

resource "aws_acm_certificate" "this" {
  domain_name       = var.domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.project}-${var.environment}-cert"
  }
}

# ACM 검증용 DNS 레코드를 Cloudflare에 생성
resource "cloudflare_record" "acm_validation" {
  for_each = {
    for dvo in aws_acm_certificate.this.domain_validation_options : dvo.domain_name => {
      name  = dvo.resource_record_name
      type  = dvo.resource_record_type
      value = dvo.resource_record_value
    }
  }

  zone_id = data.cloudflare_zone.this.id
  name    = each.value.name
  type    = each.value.type
  value   = trimsuffix(each.value.value, ".")
  ttl     = 60
  proxied = false
}

resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in cloudflare_record.acm_validation : r.hostname]
}

# ──────────────────────────── Listeners ────────────────────────────

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.this.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# ──────────────────── Cloudflare api.manyak.app -> ALB ────────────────────
# MVP: DNS-only(proxied=false) — ALB가 ACM으로 TLS 종단. Cloudflare proxy(CDN/WAF)는 후속.
resource "cloudflare_record" "api" {
  zone_id = data.cloudflare_zone.this.id
  name    = var.api_subdomain
  type    = "CNAME"
  value   = aws_lb.this.dns_name
  ttl     = 60
  proxied = false
}
