#!/usr/bin/env bash
# 美业插件闸门（执行方案 D10 / W1.6）：在一份干净副本里编译并测试美业插件与宿主的美业装配。
#
# 由 scripts/check-head-compiles.sh 在零售全量、餐饮之后调用；也可以单独跑：
#   scripts/check-beauty-plugin.sh <干净副本根目录> [把测试条数写到这个文件]
#
# 为什么要独立的 Maven 仓库：美业是独立工程（backend-plugins/shop-industry-beauty/pom.xml），
# 只能从仓库里解析宿主的公共构件（shop-store-mybatis、shop-base-auth、svc-client 等）。
# 直接 install 到 ~/.m2 会把别的工作树、别的会话正在用的 SNAPSHOT 换成这一份；
# 不 install 又只能用 ~/.m2 里不知道哪个分支留下的旧构件 —— 两头都错。
# 这里用 `cp -Rc` 把 ~/.m2/repository 克隆一份（APFS 写时复制，秒级、不占额外空间），
# 只往克隆里装，跑完删掉。
#
# 跑什么：
#   1. 克隆里装本提交的宿主公共构件；
#   2. 美业工程 install，跑全部 *Test（*IT 要一次性原生 MySQL，归 scripts/verify-beauty-persistence.mjs，
#      与餐饮段排除 *Native* 同一类）；
#   3. 宿主 shop-app 以 beauty-embedded、beauty-standalone 两种形态跑 BeautyEmbeddedWiringTest。
# 判据：失败只准是 backend-plugins/shop-industry-beauty/known-failures.txt 里的（只准变短）；
#      测试条数低于下限当作「没跑起来」。
#
# 逃生门：SKIP_BEAUTY_GATE=1（.githooks/pre-push 不读它，由 check-head-compiles.sh 判断）。

set -euo pipefail

WT="${1:?用法：check-beauty-plugin.sh <干净副本根目录> [条数输出文件]}"
COUNT_OUT="${2:-}"
BEAUTY="$WT/backend-plugins/shop-industry-beauty"
KNOWN="$BEAUTY/known-failures.txt"   # 判据取自被验的提交，不取谁的工作区
MIN_TESTS=150

if [ ! -f "$BEAUTY/pom.xml" ]; then
  echo "✗ $BEAUTY/pom.xml 不存在 —— 副本不对？"
  exit 1
fi
if [ ! -f "$KNOWN" ]; then
  echo "✗ 找不到 $KNOWN —— 没有基线就没法判断「有没有变长」，不能放行"
  exit 1
fi

SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/head-beauty.XXXXXX")"
M2="$SCRATCH/m2"
# 日志写在 SCRATCH 外：通过时一起删，失败时保留并打印路径
LOG="$(mktemp "${TMPDIR:-/tmp}/head-beauty-test.XXXXXX")"
cleanup() { rm -rf "$SCRATCH"; }
trap cleanup EXIT

START=$(date +%s)
# -c 是 macOS 的写时复制；别的文件系统上退回普通复制（慢，但结果一样）
cp -Rc "$HOME/.m2/repository" "$M2" 2>/dev/null || cp -R "$HOME/.m2/repository" "$M2"
MVN=(mvn -o -B "-Dmaven.repo.local=$M2")

echo "→ 美业插件：独立 Maven 仓库里装宿主公共构件、跑美业 *Test 与宿主美业装配（约 3 分钟）"
set +e
{
  echo "=== 宿主公共构件 ==="
  "${MVN[@]}" -q -f "$WT/backend/pom.xml" -pl shop-store-mybatis,shop-base-auth,svc-client -am install -DskipTests
  echo "HOST_RC=$?"
  echo "=== 美业工程 ==="
  # test.failure.ignore：已知失败的模块（beauty-server）也要装出构件，宿主装配测试才拿得到；
  # 失败由下面按日志逐条比对，不靠 maven 的退出码
  "${MVN[@]}" -f "$BEAUTY/pom.xml" install -Dtest='*Test' -Dsurefire.failIfNoSpecifiedTests=false \
      -Dmaven.test.failure.ignore=true
  echo "BEAUTY_RC=$?"
  for profile in beauty-embedded beauty-standalone; do
    echo "=== 宿主 -P$profile ==="
    "${MVN[@]}" -f "$WT/backend/pom.xml" -pl shop-app -am -P"$profile" test -Dtest=BeautyEmbeddedWiringTest \
        -Dsurefire.failIfNoSpecifiedTests=false
    echo "WIRING_RC_$profile=$?"
  done
} > "$LOG" 2>&1
set -e
echo "  用时 $(( $(date +%s) - START )) 秒"

for marker in HOST_RC BEAUTY_RC WIRING_RC_beauty-embedded WIRING_RC_beauty-standalone; do
  rc="$(grep -E "^$marker=" "$LOG" | tail -1 | cut -d= -f2)"
  # 美业工程的失败按清单判；其余三步必须 0
  if [ "$marker" != "BEAUTY_RC" ] && [ "${rc:-1}" != "0" ]; then
    echo ""
    echo "✗ 美业闸门的「$marker」没有成功（退出码 ${rc:-无}）。日志：$LOG"
    tail -40 "$LOG"
    exit 1
  fi
done

TOTAL="$(grep -oE '^\[(INFO|ERROR|WARNING)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$LOG" \
  | sed -E 's/.*Tests run: ([0-9]+),.*/\1/' | awk 'NF{s+=$1} END{print s+0}')"
NOW="$SCRATCH/now.txt"; BASE="$SCRATCH/known.txt"
# 美业用 test.failure.ignore 时失败清单行以 [WARNING]/[ERROR] 开头都有可能，两种都收
{ grep -E '^\[(ERROR|WARNING)\]   [A-Za-z0-9_]+\.[a-zA-Z0-9_]+' "$LOG" || true; } \
  | sed -E 's/^\[(ERROR|WARNING)\]   ([A-Za-z0-9_]+)\.([a-zA-Z0-9_]+).*/\2.\3/' | sort -u > "$NOW"
{ grep -vE '^#|^$' "$KNOWN" || true; } \
  | sed -E 's/.*\.([A-Za-z0-9_]+)\.([a-zA-Z0-9_]+)$/\1.\2/' | sort -u > "$BASE"
NEW="$(comm -23 "$NOW" "$BASE")"
FIXED="$(comm -13 "$NOW" "$BASE")"

echo "  美业 $TOTAL 跑 / $(wc -l < "$NOW" | tr -d ' ') 红（基线 $(wc -l < "$BASE" | tr -d ' ') 条）"
if [ -n "$COUNT_OUT" ]; then printf '%s\n' "$TOTAL" > "$COUNT_OUT"; fi

if [ "$TOTAL" -lt "$MIN_TESTS" ]; then
  echo ""
  echo "✗ 美业测试没有正常跑起来（识别到 $TOTAL 条，下限 $MIN_TESTS）。日志：$LOG"
  tail -30 "$LOG"
  exit 1
fi
if [ -n "$FIXED" ]; then
  echo ""
  echo "🎉 这几条已经修好了，请从 backend-plugins/shop-industry-beauty/known-failures.txt 里删掉对应行："
  echo "$FIXED" | sed 's/^/    /'
fi
if [ -n "$NEW" ]; then
  echo ""
  echo "✗ 美业新增了测试失败，这些不在 known-failures.txt 里："
  echo "$NEW" | sed 's/^/    /'
  echo "  完整日志：$LOG"
  exit 1
fi
echo "✓ 美业插件与宿主美业装配（embedded / standalone）没有新增失败"
rm -f "$LOG"
