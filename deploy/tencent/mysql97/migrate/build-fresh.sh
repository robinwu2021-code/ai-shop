#!/usr/bin/env bash
#
# 在 MySQL 9.7 上**从零建一个新环境**:空结构 + 分级种子。
# 用于起测试/预发库,或验证"全新环境能不能在 MySQL 上从零建起"。
#
# 结构从哪来(二选一,--schema):
#   dump   从现网 dump 出结构、归一排序规则(默认;等于 convert.sh 的①,但不灌真数据)
#   app    让应用自己按 Flyway 跑迁移建结构(更真实,但 132 处 uca1400 会卡住,需迁移改造后才行)
#
# 种子灌到哪一档(--level):
#   required  只灌必要(系统配置)——**生产档**,不含任何演示数据
#   test      必要 + 测试(演示业务数据)——测试/预发档
#
# 用法:
#   bash build-fresh.sh --level required --target ai_shop_fresh     # 干净的生产档结构
#   bash build-fresh.sh --level test     --suffix _staging          # 三库都建 *_staging,灌到测试档
#   WITH_ACCOUNTS=1 bash build-fresh.sh --level test --suffix _staging
set -uo pipefail
cd "$(dirname "$0")"; . ./lib.sh

LEVEL=required; SCHEMA=dump; SUFFIX="_fresh"; DBS="${DBS:-ai_shop ai_shop_inv ai_shop_job}"
while [ $# -gt 0 ]; do case "$1" in
    --level)  LEVEL="$2"; shift 2 ;;
    --schema) SCHEMA="$2"; shift 2 ;;
    --suffix) SUFFIX="$2"; shift 2 ;;
    *) die "未知参数:$1" ;;
esac; done
[ "$LEVEL" = required ] || [ "$LEVEL" = test ] || die "--level 只能是 required 或 test"

WORK="${WORK:-/tmp/mysql-fresh}"; mkdir -p "$WORK"
say "新建环境:结构=$SCHEMA · 种子档=$LEVEL · 库后缀「$SUFFIX」"

# 1) 分级种子(现从现网抽一份到 WORK;已有 out/ 可设 SEED_DIR 复用)
SEED_DIR="${SEED_DIR:-$WORK/seed}"
# 种子已存在就复用(省一次 dump)。**但它认的只是「文件在不在」** ——
# 源库换了、dump 参数改了、库里数据变了,它一概不知道,照样用旧文件。
# 2026-09-16 就栽在这儿:修好 --set-gtid-purged=OFF 之后重跑,用的还是修复前那份种子,
# 结构建好了、灌种子仍然 ERROR 3546,看起来像「没修好」。
# 要重抽就删掉 $SEED_DIR,或传 FRESH_SEED=1。
[ "${FRESH_SEED:-0}" = 1 ] && rm -f "$SEED_DIR"/seed-*.sql
if [ ! -f "$SEED_DIR/seed-required.sql" ]; then
    say "抽取分级种子 → $SEED_DIR"
    OUT="$SEED_DIR" WITH_ACCOUNTS="${WITH_ACCOUNTS:-0}" bash ./extract-seed.sh >/dev/null
fi
ok "种子就绪:required $(wc -l <"$SEED_DIR/seed-required.sql") 行 · test $( [ -f "$SEED_DIR/seed-test.sql" ] && wc -l <"$SEED_DIR/seed-test.sql" || echo 0) 行"

for db in $DBS; do
    tgt="${db}${SUFFIX}"
    say "── 建 $tgt"
    # 2) 结构
    if [ "$SCHEMA" = dump ]; then
        $SRC_DUMP $DUMP_OPTS $SRC_DUMP_EXTRA --no-data "$db" | normalize_collation > "$WORK/$db.schema.sql"
        $TGT_CLI -e "DROP DATABASE IF EXISTS \`$tgt\`; CREATE DATABASE \`$tgt\` CHARACTER SET $TARGET_CS COLLATE $TARGET_COLL"
        $TGT_CLI "$tgt" < "$WORK/$db.schema.sql" || die "$tgt 建表失败"
        ok "结构就位(dump,索引结构随之落地)"
    else
        die "--schema app 需在应用侧配置数据源后由 Flyway 跑;见 README(迁移文件的 uca1400 要先改造)"
    fi
done

# 3) 灌种子。种子里靠 `USE \`db\`;` 切库;把 USE 改写到带后缀的目标库。
load_seed() {
    local file="$1"; [ -f "$file" ] || return 0
    local expr=""; for db in $DBS; do expr="$expr s/^USE \`$db\`;/USE \`$db$SUFFIX\`;/g;"; done
    sed -E "$expr" "$file" | $TGT_CLI 2>"$WORK/seedload.err"
    grep -q ERROR "$WORK/seedload.err" && { warn "灌种子有错:"; head -5 "$WORK/seedload.err" | sed 's/^/      /'; return 1; }
    return 0
}
say "灌必要种子(REQUIRED)"; load_seed "$SEED_DIR/seed-required.sql" && ok "必要种子已灌"
if [ "$LEVEL" = test ]; then
    say "灌测试种子(TEST)"; load_seed "$SEED_DIR/seed-test.sql" && ok "测试种子已灌"
    # 运营账号：用**仓库里版本化**的那份，不从现网抽。
    # 口令是公开的（Test@12345），所以这一句只在 test 档里 —— 生产档走不到这里。
    say "灌测试运营账号(ACCOUNTS)"
    load_seed "./seed-accounts-test.sql" && ok "11 个角色账号 + 角色绑定已灌（口令 Test@12345）"
else
    # 说在明处：生产档建出来是**登不进去的**，这是有意的。
    warn "生产档不含任何运营账号 —— 第一个管理员要按 README 手工建（口令由人定、首登强制改密）"
fi

echo
ok "新环境建好:${DBS// /$SUFFIX、}$SUFFIX · 档=$LEVEL"
[ "$LEVEL" = required ] && warn "生产档不含账号:登录需另建管理员(见 README「新环境怎么登进去」)"
