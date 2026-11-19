#!/usr/bin/env bash
# UME 渠道密钥表备份到 near-pay 库（用户在 ume-db61 上执行；密钥不出服务器、不落盘、不打印）。
#
#   bash stage_keys.sh            # 备份：UME msm_mms 两张表 → nearpay_prod.stg_ume_*_20261119
#   bash stage_keys.sh --drop     # 导入完成后删掉备份表
#
# 源：本机 UME MySQL 5.7（127.0.0.1:3306，app-mysql-slave，只读即可）的
#     msm_mms.chn_mer_scan_linepay_info（LINE Pay channel secret）、msm_mms.chn_mer_card_inf（藍新 HashKey/HashIV）。
# 目标：同机 near-pay MySQL 9 容器 mysql9 的 nearpay_prod，表名加前缀 stg_ume_、后缀 _20261119。
# 做法：mysqldump 流式管道 → 改表名 → mysql9 导入；会话关闭 binlog（sql_log_bin=0），密钥不进复制与 binlog。
# 只打印行数。两个口令运行时输入，不回显、不进 argv 之外的地方。
set -euo pipefail

MYSQL57=/data0/mysql/product/bin
DOCKER="sudo -n /opt/mysql9-runtime/docker -H unix:///run/mysql9-docker.sock"
SUFFIX=20261119
SRC_TABLES=(chn_mer_scan_linepay_info chn_mer_card_inf)

read -r -p "UME msm_mms 账号（只读）: " UME_USER
read -r -s -p "UME 口令: " UME_PWD; echo
read -r -s -p "near-pay mysql9 root 口令: " NP_PWD; echo

np() { $DOCKER exec -i -e MYSQL_PWD="$NP_PWD" mysql9 mysql -uroot -N nearpay_prod "$@"; }

if [[ "${1:-}" == "--drop" ]]; then
  for t in "${SRC_TABLES[@]}"; do
    np -e "SET SESSION sql_log_bin=0; DROP TABLE IF EXISTS stg_ume_${t}_${SUFFIX};"
    echo "已删除 stg_ume_${t}_${SUFFIX}"
  done
  exit 0
fi

{
  echo "SET SESSION sql_log_bin=0;"
  for t in "${SRC_TABLES[@]}"; do echo "DROP TABLE IF EXISTS stg_ume_${t}_${SUFFIX};"; done
  MYSQL_PWD="$UME_PWD" "$MYSQL57/mysqldump" -h127.0.0.1 -P3306 -u"$UME_USER" \
      --single-transaction --skip-triggers --skip-comments --skip-add-drop-table --no-tablespaces \
      --set-gtid-purged=OFF --default-character-set=utf8mb4 msm_mms "${SRC_TABLES[@]}" \
    | sed -E "s/\`(chn_mer_scan_linepay_info|chn_mer_card_inf)\`/\`stg_ume_\1_${SUFFIX}\`/g"
} | np

for t in "${SRC_TABLES[@]}"; do
  echo "stg_ume_${t}_${SUFFIX}: $(np -e "SELECT COUNT(*) FROM stg_ume_${t}_${SUFFIX}") 行（源 $(MYSQL_PWD="$UME_PWD" "$MYSQL57/mysql" -h127.0.0.1 -P3306 -u"$UME_USER" -N -e "SELECT COUNT(*) FROM msm_mms.${t}") 行）"
done
unset UME_PWD NP_PWD
