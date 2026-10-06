# AWS に置くための設計図（Terraform）
# 今は「わたしはだれ？」を AWS に聞くだけで、何も作らない（お金はかからない）

terraform {
  required_providers {
    aws = {
      # 通訳さん（Terraform と AWS をつなぐ provider）
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  # このプロジェクトで使えるリージョンは ap-southeast-2（シドニー）だけ
  region = "ap-southeast-2"
  # aws login --profile dothething でログインしたプロファイル
  profile = "dothething"
}

# data＝作らずに「読むだけ」
data "aws_caller_identity" "me" {}

output "account_id" {
  value = data.aws_caller_identity.me.account_id
}
