#!/usr/bin/env bash
#
# 从一个源库抽取**分级种子**,排序规则已归一到 0900。产物给 build-fresh.sh 建新环境用。
#
# 分级(见 tiers.conf):
#   seed-required.sql  必要:系统配置/参考数据。任何环境(含生产)都要。
#   seed-test.sql      测试:演示业务数据(社区/商家/商品/订单/库存)。只测试环境灌。
#   seed-accounts.sql  账号:sys_ops_staff,**含密码哈希** → 当凭据处理,别提交进 git、别外传。
#   (SKIP 档不导:运行时表在新环境里就该是空的;REGION 由迁移 V31/V181 灌,不进种子。)
#
# **只读源库。** 产物是纯 INSERT(带 DROP/CREATE 关掉,不建表)——建表交给迁移或 convert.sh 的结构。
#
# 用法:
#   bash extract-seed.sh                 # 从现网抽,输出到 ./out/
#   SRC_CLI="sudo mariadb -N -B" bash extract-seed.sh
#   OUT=/tmp/seed WITH_ACCOUNTS=1 bash extract-seed.sh
set -uo pipefail
cd "$(dirname "$0")"; . ./lib.sh
CONF=./tiers.conf
OUT="${OUT:-./out}"; mkdir -p "$OUT"
WITH_ACCOUNTS="${WITH_ACCOUNTS:-0}"

# 纯数据导出:不带建表、不带 DROP;数据里若含排序规则字面量不受影响(--no-create-info 不输出 DDL)。
dump_data() { $SRC_DUMP $DUMP_OPTS --no-create-info --complete-insert --skip-add-locks "$1" "$2"; }

req="$OUT/seed-required.sql"; tst="$OUT/seed-test.sql"; acc="$OUT/seed-accounts.sql"
: > "$req"; : > "$tst"
{ echo "-- 必要种子(REQUIRED)· 生成于 $(date '+%F %T')· 排序规则已归一 $TARGET_COLL"
  echo "SET FOREIGN_KEY_CHECKS=0; SET NAMES $TARGET_CS;"; } >> "$req"
{ echo "-- 测试种子(TEST)· 生成于 $(date '+%F %T')· 需先灌 seed-required.sql"
  echo "SET FOREIGN_KEY_CHECKS=0; SET NAMES $TARGET_CS;"; } >> "$tst"

declare -A cnt=([required]=0 [test]=0 [skip]=0 [region]=0 [accounts]=0)
for db in ai_shop ai_shop_inv ai_shop_job; do
    while read -r t; do
        tier=$(tier_of "$CONF" "$db" "$t")
        cnt[$tier]=$(( ${cnt[$tier]} + 1 ))
        # INSERT 不带库名前缀,所以每张表前加 USE,灌的时候才知道进哪个库。
        case "$tier" in
            required) { echo "USE \`$db\`;  -- [$db.$t]"; dump_data "$db" "$t"; } >> "$req" ;;
            test)     { echo "USE \`$db\`;  -- [$db.$t]"; dump_data "$db" "$t"; } >> "$tst" ;;
            skip|region) : ;;   # 不导
            accounts)
                if [ "$WITH_ACCOUNTS" = 1 ]; then
                    { echo "-- ⚠ 含密码哈希,当凭据处理"; echo "USE \`$db\`;"; dump_data "$db" "$t"; } > "$acc"
                fi ;;
        esac
    done < <(list_tables "$db")
done

say "分级抽取完成(源只读):"
ok "必要 REQUIRED : ${cnt[required]} 张 → $req  ($(wc -l <"$req") 行)"
ok "测试 TEST     : ${cnt[test]} 张 → $tst  ($(wc -l <"$tst") 行)"
ok "运行时 SKIP   : ${cnt[skip]} 张(不导,新环境该为空)"
ok "参考 REGION   : ${cnt[region]} 张(由迁移 V31/V181 灌,不进种子)"
if [ "$WITH_ACCOUNTS" = 1 ]; then
    warn "账号 ACCOUNTS : 已写 $acc —— **含密码哈希,别提交 git、别外传**;导入后改口令"
else
    warn "账号 ACCOUNTS : 未导(WITH_ACCOUNTS=1 才导);新环境登录见 README"
fi
