# 設計図の中で使う「変数」（あとから値を変えられる名前）

# 作るものの名前の頭に付ける文字（例：taskmanagement-vpc）
variable "project_name" {
  type    = string
  default = "taskmanagement"
}

# 画面（80 番）を見てよい、家の IP アドレス（例：203.0.113.10/32。/32＝その 1 台だけ）
# 住んでいる場所の手がかりになるので、値は Git に入らない terraform.tfvars に書く
# 家の IP は変わることがある（ルーターの再起動など）。変わったら tfvars を直して apply する
variable "home_ip_cidr" {
  type = string

  # 世界中（0.0.0.0/0）を入れてしまう事故を防ぐ。/32（1 台だけ）しか受け付けない
  validation {
    condition     = can(cidrhost(var.home_ip_cidr, 0)) && endswith(var.home_ip_cidr, "/32")
    error_message = "home_ip_cidr は「家の IP/32」の形で書いてください（例：203.0.113.10/32）。"
  }
}

# EC2 のドアに取り付ける錠前（SSH の公開鍵 .pub）のファイルの場所。鍵（秘密鍵）ではない
variable "ssh_public_key_path" {
  type = string
}
