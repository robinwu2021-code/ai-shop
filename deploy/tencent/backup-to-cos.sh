#!/bin/bash
# 每日备份 → COS `hxmall-backup-1301656997`
#
# 装在服务器 /opt/ai-shop/backup-to-cos.sh，由 /etc/cron.d/ai-shop-backup 每天 03:20 跑。
#
# **只在本机留一份不叫备份** —— 机器没了备份跟着没。所以落地即上传，
# 本机只保留最近 3 天用于快速回滚，其余交给 COS 的生命周期规则。
#
# ⚠️ 当前用的是后端那把 COS 密钥（/opt/ai-shop/shop-app.env）。
#    按 cos-buckets.md §四 应该换成**只能写 backup 桶**的子账号密钥 ——
#    发子账号密钥要在控制台做，换的时候只需改下面的 ENV_FILE 指向新文件。
set -euo pipefail

ENV_FILE=/opt/ai-shop/shop-app.env
BUCKET=hxmall-backup-1301656997
REGION=ap-guangzhou
LOCAL=/var/backups/ai-shop
KEEP_DAYS=3
DAY=$(date +%Y%m%d)

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

mkdir -p "$LOCAL"

# ── 逻辑备份 ──
# --single-transaction：InnoDB 下不锁表，备份期间照常接单
# --routines --events：存储过程与事件也要，否则恢复出来的库少东西且不报错
DUMP="$LOCAL/ai_shop-$DAY.sql.gz"
mariadb-dump --single-transaction --routines --events --default-character-set=utf8mb4 \
  -u"${SHOP_DB_USER:-shop}" -p"${SHOP_DB_PASS:-shop}" ai_shop | gzip -9 > "$DUMP"

SIZE=$(stat -c%s "$DUMP")
if [ "$SIZE" -lt 10240 ]; then
  echo "备份文件只有 $SIZE 字节，判定为失败（库空了或 dump 报错）" >&2
  exit 1
fi

# ── 上传 ──
python3 /opt/ai-shop/cos_put.py "$DUMP" "$BUCKET" "$REGION" \
  "db/ai_shop-$DAY.sql.gz" "application/gzip"

# ── 本机只留最近几天 ──
find "$LOCAL" -name 'ai_shop-*.sql.gz' -mtime +$KEEP_DAYS -delete

echo "$(date -Is) 备份完成 $(numfmt --to=iec "$SIZE")"
