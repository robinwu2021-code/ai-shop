#!/bin/bash
# 每日备份 → COS `hxmall-backup-1301656997`
#
# 装在服务器 /data/app/ai-shop/ops/backup-to-cos.sh，由 /etc/cron.d/ai-shop-backup 每天 03:20 跑。
#
# **只在本机留一份不叫备份** —— 机器没了备份跟着没。所以落地即上传，
# 本机只保留最近 3 天用于快速回滚，其余交给 COS 的生命周期规则。
#
# ⚠️ 当前用的是后端那把 COS 密钥（/data/app/ai-shop/shop-app/shop-app.env）。
#    按 cos-buckets.md §四 应该换成**只能写 backup 桶**的子账号密钥 ——
#    发子账号密钥要在控制台做，换的时候只需改下面的 ENV_FILE 指向新文件。
set -euo pipefail

ENV_FILE=/data/app/ai-shop/shop-app/shop-app.env
BUCKET=hxmall-backup-1301656997
REGION=ap-guangzhou
LOCAL=/data/backup/ai-shop/db
KEEP_DAYS=3
DAY=$(date +%Y%m%d)

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

MYSQLDUMP="${MYSQLDUMP:-/opt/mysql/current/bin/mysqldump}"
[ -x "$MYSQLDUMP" ] || { echo "找不到 mysqldump：$MYSQLDUMP" >&2; exit 1; }

mkdir -p "$LOCAL"

# ── 逻辑备份 ──
# --single-transaction：InnoDB 下不锁表，备份期间照常接单
# --routines --events：存储过程与事件也要，否则恢复出来的库少东西且不报错
DUMP="$LOCAL/ai_shop-$DAY.sql.gz"
# 2026-09-16 切 MySQL 9.7：客户端换成 mysql97 自带的那个。
# `mariadb-dump` 连的是 3306 —— MariaDB 停掉之后它每天 03:20 直接失败，
# 而失败只写进备份日志，不改这里就是「备份一直在跑、其实一份都没有」。
# 顺带把密码从命令行挪走：`-p<密码>` 在 dump 的几分钟里对 `ps` 全可见。
# 走 socket，不加 --protocol=TCP：root 是 auth_socket 认证（以 OS root 身份免密），
# 走 TCP 会变成「需要密码」而当场 1045 —— 迁移脚本用的也是这种连法。
"$MYSQLDUMP" --defaults-file=/etc/mysql97/my.cnf -uroot \
  --single-transaction --routines --events --default-character-set=utf8mb4 \
  ai_shop | gzip -9 > "$DUMP"

SIZE=$(stat -c%s "$DUMP")
if [ "$SIZE" -lt 10240 ]; then
  echo "备份文件只有 $SIZE 字节，判定为失败（库空了或 dump 报错）" >&2
  exit 1
fi

# ── 上传 ──
python3 /data/app/ai-shop/ops/cos_put.py "$DUMP" "$BUCKET" "$REGION" \
  "db/ai_shop-$DAY.sql.gz" "application/gzip"

# ── 本机只留最近几天 ──
find "$LOCAL" -name 'ai_shop-*.sql.gz' -mtime +$KEEP_DAYS -delete

echo "$(date -Is) 备份完成 $(numfmt --to=iec "$SIZE")"
