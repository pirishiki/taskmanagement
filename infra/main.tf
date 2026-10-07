# AWS に置くための設計図（Terraform）
# このファイルは土台（通訳さん＝provider の設定）。作るものは役割ごとに別のファイルに書く
#   variables.tf：変数　vpc.tf：VPC（敷地）　outputs.tf：作ったあとに見せる値

terraform {
  # Terraform 本体のバージョン（古い Terraform で動かして、書き方が通じないのを防ぐ）
  required_version = ">= 1.16"

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

  # この設計図で作るものすべてに、自動で付ける名札（どのアプリの部品か、Terraform で作ったものか）
  default_tags {
    tags = {
      Project   = var.project_name
      ManagedBy = "terraform"
    }
  }
}

# data＝作らずに「読むだけ」
data "aws_caller_identity" "me" {}
