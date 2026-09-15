#!/usr/bin/env bash
# MariaDB → MySQL 9.7 迁移工具的公共函数。被 convert.sh / extract-seed.sh / build-fresh.sh 引用。
# 只放函数与变量,不自己跑任何东西。

# ── 连接(可用环境变量覆盖)──────────────────────────────────────────────
# 目标:MySQL 9.7。必须带 --defaults-file,否则客户端会去读 MariaDB 的 my.cnf。
MY_DEFAULTS="${MY_DEFAULTS:-/etc/mysql97/my.cnf}"
MY_BIN="${MY_BIN:-/opt/mysql/current/bin/mysql}"
MY_DUMP_BIN="${MY_DUMP_BIN:-$(dirname "$MY_BIN")/mysqldump}"
TGT_CLI="${TGT_CLI:-sudo $MY_BIN --defaults-file=$MY_DEFAULTS}"

# 源。**2026-09-16 切库之后默认改成 MySQL** —— 现网真源已经是它。
# 之前默认是 `sudo mariadb`,而 MariaDB 切完就停了:extract-seed / build-fresh
# 会连不上,而它们平时不跑,这种失败要等到「哪天要建个新环境」才会被发现。
# convert.sh 是专做 MariaDB→MySQL 的,它自己在开头把源覆盖回 MariaDB。
SRC_DUMP="${SRC_DUMP:-sudo $MY_DUMP_BIN --defaults-file=$MY_DEFAULTS}"
SRC_CLI="${SRC_CLI:-sudo $MY_BIN --defaults-file=$MY_DEFAULTS -N -B}"

# 目标排序规则(归一化目标)。全库统一,bin 列除外。
TARGET_COLL="${TARGET_COLL:-utf8mb4_0900_ai_ci}"
TARGET_CS="${TARGET_CS:-utf8mb4}"

# mariadb-dump 的公共选项:一致性快照、不带 MariaDB 专有 gtid 选项(MySQL 不认)、不锁全表。
DUMP_OPTS="--single-transaction --no-tablespaces --skip-lock-tables"
# 源是 MySQL 且开了 binlog 时,mysqldump 会在文件开头写一句
# `SET @@GLOBAL.GTID_PURGED=...`。把这种 dump 灌进任何已有 GTID 的实例,
# 会当场 ERROR 3546「the added gtid set must not overlap」—— 建库直接失败。
# mariadb-dump 不认这个参数,所以 convert.sh 把它置空。
SRC_DUMP_EXTRA="${SRC_DUMP_EXTRA:---set-gtid-purged=OFF}"

say()  { printf '\033[36m›\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# 把排序规则归一到目标 —— **只应作用在 DDL(建表语句)上,别碰数据段**。
# uca1400 / unicode_520 / unicode_ci → 0900;utf8mb4_bin 不动(9 个 JSON 列故意二进制)。
normalize_collation() {
    sed -E "s/utf8mb4_uca1400_ai_ci/$TARGET_COLL/g;
            s/utf8mb4_unicode_520_ci/$TARGET_COLL/g;
            s/utf8mb4_unicode_ci\b/$TARGET_COLL/g"
}

# 列出一个库的全部基础表(只读源库)。
list_tables() { $SRC_CLI -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$1' AND table_type='BASE TABLE' ORDER BY table_name"; }

# 源库某表行数(现网真实值,用于对账)。
src_count() { $SRC_CLI -e "SELECT COUNT(*) FROM \`$1\`.\`$2\`"; }
# 目标库某表行数。
tgt_count() { $TGT_CLI -N -B -e "SELECT COUNT(*) FROM \`$1\`.\`$2\`" 2>/dev/null; }

# 读 tiers.conf,把某库某表归到 required/test/skip/region/accounts 之一。
# 用法:tier_of <conf> <db> <table>  →  打印档名
tier_of() {
    local conf="$1" db="$2" t="$3"
    # 后缀通配 SKIP
    local suf; for suf in $(awk '/^SKIP_SUFFIX/{$1="";print}' "$conf"); do
        [[ "$t" == *"$suf" ]] && { echo skip; return; }
    done
    local tag
    for tag in REQUIRED SKIP REGION ACCOUNTS; do
        if awk -v d="$db" -v tag="$tag" '$1==tag && $2==d{for(i=3;i<=NF;i++)print $i}' "$conf" | grep -qxF "$t"; then
            echo "${tag,,}"; return
        fi
    done
    echo test   # 默认:业务/演示数据
}
