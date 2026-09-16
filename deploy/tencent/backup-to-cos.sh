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
# ── 备哪几个库 ────────────────────────────────────────────────────────
#
# **三个都要。** 2026-09-16 之前这里只备 ai_shop —— 而 ai_shop_inv（库存台账、
# 预留、出入库流水）与 ai_shop_job（任务定义与运行历史）**一份备份都没有**。
# 那是切库之前就存在的洞，不是切库引入的；但切完之后它们是确确实实的生产依赖，
# 丢了就是丢了，而且 COS 上连个能回滚的文件都找不到。
DBS="${DBS:-ai_shop ai_shop_inv ai_shop_job}"

MYCLI="/opt/mysql/current/bin/mysql --defaults-file=/etc/mysql97/my.cnf -uroot -N -B"
TOTAL=0

for DB in $DBS; do
    DUMP="$LOCAL/$DB-$DAY.sql.gz"

    # 2026-09-16 切 MySQL 9.7：客户端换成 mysql97 自带的那个。
    # `mariadb-dump` 连的是 3306 —— MariaDB 停掉之后它每天 03:20 直接失败，
    # 而失败只写进备份日志，不改这里就是「备份一直在跑、其实一份都没有」。
    #
    # ⚠️ `--set-gtid-purged=OFF` 不是可选项：开了 binlog 之后 mysqldump 会在文件开头写
    # `SET @@GLOBAL.GTID_PURGED=...`，把这份备份灌进任何已有 GTID 的实例会当场 ERROR 3546。
    # 也就是说，**不加这一句备出来的文件是恢复不了的** —— 而它照样几 M、照样传上 COS、
    # 照样每天「备份成功」。切库当天备的第一份就带着它。
    #
    # 走 socket，不加 --protocol=TCP：root 是 auth_socket 认证（以 OS root 身份免密），
    # 走 TCP 会变成「需要密码」而当场 1045 —— 迁移脚本用的也是这种连法。
    "$MYSQLDUMP" --defaults-file=/etc/mysql97/my.cnf -uroot \
      --single-transaction --routines --events --default-character-set=utf8mb4 \
      --set-gtid-purged=OFF \
      "$DB" | gzip -9 > "$DUMP"

    # ── 判据：**建表数 + 数据行数都要与库对得上** ────────────────────
    #
    # 原来判的是「文件大于 10KB」。那把尺量不到内容，而且对小库会误判：
    # ai_shop_inv 备出来只有 40K，正常的备份也贴着阈值走。
    #
    # ⚠️ **两条判据缺一不可，这是消融验出来的**（2026-09-16）：
    # 拿一份 `--no-data`（只有结构、数据全丢）的 dump 去试，
    #   · 只数建表数 → 21 == 21，**放行**；
    #   · 只看大小   → 7916 字节 < 10KB，拦下（这次侥幸）。
    # 写这段注释时我本来断言「只有结构的 dump 照样远超 10KB」——**实测是错的**。
    # 所以不能只留一条：建表数管「表少了」，INSERT 数管「数据没了」。
    WANT=$($MYCLI -e "SELECT COUNT(*) FROM information_schema.tables
                       WHERE table_schema='$DB' AND table_type='BASE TABLE'")
    GOT=$(zcat "$DUMP" | grep -c '^CREATE TABLE' || true)
    [ "${WANT:-0}" -gt 0 ] || { echo "库 $DB 里一张表都没有，拒绝把它当成备份" >&2; exit 1; }
    if [ "$GOT" != "$WANT" ]; then
        echo "$DB 备份不完整：dump 里 $GOT 张建表，库里 $WANT 张" >&2
        exit 1
    fi

    # 有数据的库，dump 里必须真的有 INSERT。空库（新环境）允许为 0，所以先问库。
    ROWS=$($MYCLI -e "SELECT COALESCE(SUM(table_rows),0) FROM information_schema.tables
                       WHERE table_schema='$DB' AND table_type='BASE TABLE'")
    INS=$(zcat "$DUMP" | grep -c '^INSERT INTO' || true)
    if [ "${ROWS:-0}" -gt 0 ] && [ "$INS" -eq 0 ]; then
        echo "$DB 备份里一条 INSERT 都没有，而库里约有 $ROWS 行 —— 这是「只备到了结构」" >&2
        exit 1
    fi

    python3 /data/app/ai-shop/ops/cos_put.py "$DUMP" "$BUCKET" "$REGION" \
      "db/$DB-$DAY.sql.gz" "application/gzip"

    SIZE=$(stat -c%s "$DUMP")
    TOTAL=$((TOTAL + SIZE))
    echo "  $DB  $GOT 张表 · $INS 条 INSERT · $(numfmt --to=iec "$SIZE")"

    # ── 本机只留最近几天 ──
    find "$LOCAL" -name "$DB-*.sql.gz" -mtime +$KEEP_DAYS -delete
done

echo "$(date -Is) 备份完成 $(echo $DBS | wc -w) 个库，合计 $(numfmt --to=iec "$TOTAL")"
