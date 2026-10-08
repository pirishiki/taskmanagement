#!/bin/bash
# EC2 で、送ったアプリを正しい場所に並べて動かす手順（荷ほどき。イシュー #61）
#
# 使い方（root で実行する。Session Manager の Run Command か、EC2 に入って sudo で）
#   sudo bash /home/ec2-user/deploy/install.sh <RDS の住所:番号> <金庫の場所>
#   値は手元の infra で terraform output db_endpoint / db_master_secret_arn を見る
#
# 先に、手元から scp で ec2-user の部屋に送っておくもの
#   dist/（npm run build）・backend-*.jar（./gradlew bootJar）・deploy/・nginx/default.conf.template
#
# 何度実行してもよい（RDS を作り直して住所や金庫が変わったときも、これをもう一度実行する）

set -euo pipefail

DB_HOST="${1:?1 つ目に RDS の住所:番号（terraform output db_endpoint）を指定してください}"
DB_SECRET_ARN="${2:?2 つ目に金庫の場所（terraform output db_master_secret_arn）を指定してください}"
SRC=/home/ec2-user

# 1. アプリ専用の利用者（ログインできない。アプリの部屋の鍵だけを持つ）
id taskmanagement >/dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin taskmanagement

# 2. アプリ本体と、起動の手順書を /opt/taskmanagement に置く
install -d -m 755 /opt/taskmanagement
# backend-*.jar のうち、部品だけの箱（-plain.jar）ではないものを 1 つ選ぶ
jar=""
for f in "$SRC"/backend-*.jar; do
  case "$f" in
    *-plain.jar) ;;
    *) jar="$f" ;;
  esac
done
[ -f "$jar" ] || { echo "backend-*.jar が $SRC にありません（./gradlew bootJar して scp で送る）" >&2; exit 1; }
install -m 644 "$jar" /opt/taskmanagement/app.jar
install -m 755 "$SRC/deploy/start.sh" /opt/taskmanagement/start.sh

# 3. RDS の住所と金庫の場所（パスワードではない）。root と taskmanagement だけが読める
install -d -m 750 -g taskmanagement /etc/taskmanagement
cat > /etc/taskmanagement/app.env <<ENV
DB_HOST=${DB_HOST}
DB_SECRET_ARN=${DB_SECRET_ARN}
ENV
chown root:taskmanagement /etc/taskmanagement/app.env
chmod 640 /etc/taskmanagement/app.env

# 4. 画面のファイルを Nginx の置き場所に入れ替える。Windows から送ると ec2-user しか読めないので、だれでも読めるようにする
rm -rf /usr/share/nginx/html/*
cp -r "$SRC"/dist/. /usr/share/nginx/html/
chmod -R a+rX /usr/share/nginx/html

# 5. Nginx の設定（#53 のひな形）。空欄 ${BACKEND_ORIGIN} を、同じ家の中の Spring Boot（127.0.0.1:8080）で埋める
# 一重引用符はわざと：シェルに埋めさせず、「${BACKEND_ORIGIN}」という文字そのものを探す
# shellcheck disable=SC2016
sed's|${BACKEND_ORIGIN}|http://127.0.0.1:8080|g' "$SRC/nginx/default.conf.template" > /etc/nginx/conf.d/default.conf
nginx -t
systemctl reload nginx

# 6. 目覚まし時計（systemd）を置いて、アプリを（再）起動する
install -m 644 "$SRC/deploy/taskmanagement.service" /etc/systemd/system/taskmanagement.service
systemctl daemon-reload
systemctl enable taskmanagement
systemctl restart taskmanagement

# 7. アプリが起きるのを待つ（最大 2 分）。Nginx 経由で /api/health を聞く
for _ in $(seq 1 24); do
  if curl -fs http://127.0.0.1/api/health; then
    echo
    echo "OK：アプリが動いています"
    exit 0
  fi
  sleep 5
done
echo "アプリが 2 分たっても起きません。記録を見てください：sudo journalctl -u taskmanagement -n 100" >&2
exit 1
