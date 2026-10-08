# VPC：自分専用のネットワーク（塀で囲った敷地）。部品はすべて無料（イシュー #57）
#
#   VPC（10.0.0.0/16）
#   ├─ パブリックサブネット 2a（10.0.1.0/24）── 道案内 ──▶ 門（インターネットゲートウェイ）　… EC2 を置く
#   ├─ プライベートサブネット 2a（10.0.11.0/24）　外への道案内なし　… RDS を置く
#   └─ プライベートサブネット 2b（10.0.12.0/24）　外への道案内なし　… RDS を置く（RDS は 2 つ以上のデータセンターが必要）
#
# NAT ゲートウェイ（奥の部屋から外に出るための部品）は、置くだけで毎月お金がかかるので作らない

# 敷地。10.0.0.0/16 は、敷地の中で使う住所の範囲（10.0.○.○ の 65,536 個）
# Trivy の指摘（通信の記録＝フローログがない）は、わざと（イシュー #63）：記録の置き場所（CloudWatch Logs）と許可証が増え、
# 少しお金もかかる。練習では取らない（本番なら取る）。この指摘は部品の上の目印が効かないので、.trivyignore.yaml に書いた
resource "aws_vpc" "main" {
  cidr_block = "10.0.0.0/16"
  # 敷地の中で、住所の代わりに名前（〇〇.internal など）を使えるようにする。RDS の接続先は名前で渡されるため
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.project_name}-vpc"
  }
}

# 敷地の門（道路＝インターネットへの出入口）
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-igw"
  }
}

# 表の庭（パブリックサブネット）。EC2 を置く
resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = "ap-southeast-2a"
  # ここに置いたものに、自動でパブリック IP を付けない。付けるかどうかは EC2 の設計図ではっきり決める
  # （練習の EC2 では、デフォルトのサブネットのこの設定が優先され、付けないと書いたのに付いた）
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.project_name}-public-a"
  }
}

# 奥の部屋（プライベートサブネット）×2。RDS を置く。外への道案内がないので、インターネットとは行き来できない
resource "aws_subnet" "private_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.11.0/24"
  availability_zone = "ap-southeast-2a"

  tags = {
    Name = "${var.project_name}-private-a"
  }
}

resource "aws_subnet" "private_b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.12.0/24"
  availability_zone = "ap-southeast-2b"

  tags = {
    Name = "${var.project_name}-private-b"
  }
}

# 表の庭の道案内の看板：「敷地の外（0.0.0.0/0＝どこでも）へは、門を通って出る」
# これは門番（だれが入れるか）ではなく、道案内（どこを通るか）。入口を開ける設定ではない
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.project_name}-public-rt"
  }
}

# 道案内の看板を、表の庭だけに立てる（奥の部屋には立てない）
resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# VPC を作ると最初から付いてくる門番（デフォルトのセキュリティグループ）を、だれも通さない設定にする
# ingress（入口）も egress（出口）も書かない＝全部止める。EC2・RDS には、それぞれ専用の門番を別に作る
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-default-sg-locked"
  }
}
