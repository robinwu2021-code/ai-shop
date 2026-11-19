#!/usr/bin/env bash
# UME 渠道密钥表备份到 near-pay 库（在 ume-db61 上执行；密钥不出服务器、不落盘、不打印）。
#
#   bash stage_keys.sh --from-backup [DUMP.tar.gz]   # 从 .61 上 .60 的每日全库备份抽两张表（默认最新一份），不需要 UME 口令
#   bash stage_keys.sh --live                        # 从本机 UME 5.7（127.0.0.1:3306）在线导出，需要 UME 账号口令
#   bash stage_keys.sh --drop                        # 导入完成后删掉备份表
#
# 口令从标准输入逐行读（--live：UME 账号、UME 口令、mysql9 root 口令；其余模式只读 mysql9 root 口令），不回显、不打印。
# 目标：同机 near-pay MySQL 9 容器 mysql9 的 nearpay_prod.stg_ume_<表>_20261119；会话关闭 binlog（sql_log_bin=0）。
# 只打印行数。
set -euo pipefail

MYSQL57=/data0/mysql/product/bin
DOCKER="sudo -n /opt/mysql9-runtime/docker -H unix:///run/mysql9-docker.sock"
BACKUPS=/data0/data_bak/60/mysqldump_master
SUFFIX=20261119
SRC_TABLES=(chn_mer_scan_linepay_info chn_mer_card_inf)
MODE=${1:-}

if [[ "$MODE" == "--live" ]]; then
  read -r -p "UME msm_mms 账号（只读）: " UME_USER
  read -r -s -p "UME 口令: " UME_PWD; echo
fi
read -r -s -p "near-pay mysql9 root 口令: " NP_PWD; echo

np() { $DOCKER exec -i -e MYSQL_PWD="$NP_PWD" mysql9 mysql -uroot -N nearpay_prod "$@"; }
rename() { sed -E "s/\`(chn_mer_scan_linepay_info|chn_mer_card_inf)\`/\`stg_ume_\1_${SUFFIX}\`/g"; }

# 全库 dump 里只取 msm_mms 的两张表：建表语句与 INSERT；过了 msm_mms 就停止解压。
extract() {
  awk '
    /^USE `/ { db = $2; gsub(/[`;]/, "", db); if (seen && db != "msm_mms") exit; next }
    db != "msm_mms" { next }
    { seen = 1 }
    /^(DROP TABLE IF EXISTS|CREATE TABLE) `(chn_mer_scan_linepay_info|chn_mer_card_inf)`/ { blk = 1 }
    blk { print; if ($0 ~ /;$/) blk = 0; next }
    /^INSERT INTO `(chn_mer_scan_linepay_info|chn_mer_card_inf)` / { print }
  '
}

case "$MODE" in
  --drop)
    for t in "${SRC_TABLES[@]}"; do
      np -e "SET SESSION sql_log_bin=0; DROP TABLE IF EXISTS stg_ume_${t}_${SUFFIX};"
      echo "已删除 stg_ume_${t}_${SUFFIX}"
    done
    exit 0 ;;
  --from-backup)
    DUMP=${2:-$(sudo -n ls -t "$BACKUPS"/mysqldump-*.tar.gz | head -1)}
    echo "源：$DUMP（.60 全库备份，抽 msm_mms 两张表，约需 10–30 分钟）"
    {
      echo "SET SESSION sql_log_bin=0;"
      for t in "${SRC_TABLES[@]}"; do echo "DROP TABLE IF EXISTS stg_ume_${t}_${SUFFIX};"; done
      { sudo -n tar -xzOf "$DUMP" 2>/dev/null || true; } | extract | rename
    } | np ;;
  --live)
    {
      echo "SET SESSION sql_log_bin=0;"
      for t in "${SRC_TABLES[@]}"; do echo "DROP TABLE IF EXISTS stg_ume_${t}_${SUFFIX};"; done
      MYSQL_PWD="$UME_PWD" "$MYSQL57/mysqldump" -h127.0.0.1 -P3306 -u"$UME_USER" \
          --single-transaction --skip-triggers --skip-comments --skip-add-drop-table --no-tablespaces \
          --set-gtid-purged=OFF --default-character-set=utf8mb4 msm_mms "${SRC_TABLES[@]}" | rename
    } | np
    unset UME_PWD ;;
  *)
    sed -n '2,10p' "$0"; exit 2 ;;
esac

for t in "${SRC_TABLES[@]}"; do
  echo "stg_ume_${t}_${SUFFIX}: $(np -e "SELECT COUNT(*) FROM stg_ume_${t}_${SUFFIX}") 行"
done
unset NP_PWD
