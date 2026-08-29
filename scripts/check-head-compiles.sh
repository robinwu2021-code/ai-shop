#!/usr/bin/env bash
#
# 从 HEAD 拉一份干净副本编一遍 —— 多会话共用一个工作目录时，这是唯一可靠的判据。
#
# 为什么不能在当前工作目录里编：工作目录里有所有人尚未提交的改动，它们会把
# 「HEAD 缺东西」这件事盖住。2026-08-24 就是这么漏了 30 小时 ——
# 调用方进了提交、定义还躺在某个会话的工作区里，本地一切正常，
# 任何人从 HEAD 拉一份干净副本都编不过，于是那 30 小时里没有人跑得成全量测试。
#
# 为什么不能只扫 git status：`??` 只抓得到「新文件」这一半。另一半是
# **嵌套类型/新方法加在已有文件里**（GeoPoiCacheMapper 嵌在 PlatformMappers.java、
# SysRegion.rural、CommunityVO 的新字段），它们停在 M 列、混在一堆无关改动中间，
# 比 `??` 更难看见。那次让 HEAD 编译过需要 27 个文件，其中 14 个是 M 不是 `??`。
#
# 编完之后**还要跑全量测试**，判据是「只准变短，不准变长」：
# 与 backend/known-failures.txt 比对，新增失败就挡住推送。
#
# 为什么闸门只能立在这里：云端 CI 编不了后端（私有父 POM 只在本机 ~/.m2），
# .github/workflows/backend.yml 每次都是 skipped —— 查过最近的运行记录，一次没跑过。
# 于是 1205 条测试此前**没有任何地方在自动跑**，唯一的防线是「谁记得在本机跑一遍」。
#
# 耗时实测 3 分 07 秒（2026-08-25，含编译）。只在本次推送动过 backend/ 时触发
# （判断在 .githooks/pre-push 里），所以前端推送不受影响。
#
# 用法：
#   scripts/check-head-compiles.sh            # 检查 HEAD
#   scripts/check-head-compiles.sh <commit>   # 检查任意提交
#
# 逃生门：
#   SKIP_COMPILE_GATE=1   整个闸门都跳过（.githooks/pre-push 里判断）
#   SKIP_TEST_GATE=1      只编译、不跑测试
#
# 什么时候该用 SKIP_TEST_GATE：**共享工作区里别人写到一半时不该用** ——
# 这个脚本跑的是干净 HEAD 副本，别人未提交的半成品影响不到它。
# 真正该用的场合是「你已经知道这次全量会红成一片、且原因与你无关」，
# 比如刚有人把一支坏迁移推进了 HEAD，你正在推的恰好是修它的那一条。
set -euo pipefail

REF="${1:-HEAD}"
ROOT="$(git rev-parse --show-toplevel)"
WT="$(mktemp -d "${TMPDIR:-/tmp}/head-compiles.XXXXXX")"

cleanup() { git -C "$ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || rm -rf "$WT"; }
trap cleanup EXIT

echo "→ 从 $REF 拉一份干净副本：$WT"
git -C "$ROOT" worktree add -f --detach "$WT" "$REF" >/dev/null

# JDK 21：显式指定，别赌 shell 里 JAVA_HOME 是什么
for c in /opt/homebrew/opt/openjdk@21 /usr/lib/jvm/java-21-openjdk "$(/usr/libexec/java_home -v 21 2>/dev/null || true)"; do
  if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then export JAVA_HOME="$c"; break; fi
done
if [ -z "${JAVA_HOME:-}" ]; then echo "✗ 找不到 JDK 21"; exit 1; fi
export PATH="$JAVA_HOME/bin:$PATH"

# test-compile 而不是 compile：今天这场里主源码一直编得过，
# 红的是**测试源码**（改了记录的构造器，漏掉一个调用方）。只编主源码等于没编。
echo "→ mvn test-compile（含测试源码）"
if (cd "$WT/backend" && mvn -o -q -B -DskipTests test-compile); then
  echo "✓ $REF 从干净副本可以编译"
else
  echo ""
  echo "✗ $REF 从干净副本编译不过。"
  echo "  多半是「定义还没提交，调用方已经进了 HEAD」。查法："
  echo "    git status --porcelain            # 看 ?? 的新文件"
  echo "    git diff --stat                   # 也要看 M —— 新符号常是嵌套类型/新方法"
  echo "  别替别人提交，把缺的符号名报给对应会话。"
  exit 1
fi

if [ "${SKIP_TEST_GATE:-}" = "1" ]; then
  echo "⚠ 已跳过测试闸门（SKIP_TEST_GATE=1）"
  exit 0
fi

KNOWN="$ROOT/backend/known-failures.txt"
if [ ! -f "$KNOWN" ]; then
  echo "✗ 找不到 $KNOWN —— 没有基线就没法判断「有没有变长」，不能放行"
  exit 1
fi

# 日志**写在 worktree 外面**：退出时 trap 会把 worktree 整个删掉，
# 写在里面的话，失败信息里那句「完整日志：…」指向的文件已经不存在了。
# 模板里的 X **必须在结尾**：BSD 的 mktemp（macOS）只替换结尾那串 X，
# 写成 `head-test.XXXXXX.log` 会原样造出一个名叫 XXXXXX 的文件，
# 于是失败信息里给出的路径每次都一样、还会互相覆盖。
LOG="$(mktemp "${TMPDIR:-/tmp}/head-test.XXXXXX")"
echo "→ mvn test（全量，约 3 分钟）"
START=$(date +%s)
set +e
(cd "$WT/backend" && mvn -o -B test) > "$LOG" 2>&1
set -e
echo "  用时 $(( $(date +%s) - START )) 秒"

# ── 保险：测试没跑起来 ≠ 通过 ──
#
# maven 因为别的原因中途死掉时，「没有失败」和「一条都没跑」在输出上长得一样。
# 没有这一步的话，闸门会在最该拦住的时候安静放行。
TOTAL="$(grep -oE '^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$LOG" \
  | tail -1 | sed -E 's/.*Tests run: ([0-9]+),.*/\1/')"
if [ -z "$TOTAL" ] || [ "$TOTAL" -lt 800 ]; then
  echo ""
  echo "✗ 全量测试没有正常跑起来（识别到 ${TOTAL:-0} 条，正常在 1200 上下）。"
  echo "  这不是「全绿」，是**没测**。日志：$LOG"
  tail -30 "$LOG"
  exit 1
fi

# surefire 的失败清单长这样（:行号 或 » 异常 两种收尾都有）：
#   [ERROR]   M9bBizGoodsFlowTest.handTypedSpecStillLands:149 期望 ...
#   [ERROR]   SpecLibraryCoverageTest.exactlyOnePrimary » IllegalState ...
# 清单里存的是全限定名，这里两边都归到 Class.method 再比。
# ⚠ 两个 grep 都要 `|| true`：一条失败都没有时 grep 返回 1，
# 而 `set -e` 会让脚本**在最该放行的时候当场死掉**（写的时候就踩了一次）。
NOW="$WT/now.txt"; BASE="$WT/known.txt"
{ grep -E '^\[ERROR\]   [A-Za-z0-9_]+\.[a-zA-Z0-9_]+' "$LOG" || true; } \
  | sed -E 's/^\[ERROR\]   ([A-Za-z0-9_]+)\.([a-zA-Z0-9_]+).*/\1.\2/' | sort -u > "$NOW"
{ grep -vE '^#|^$' "$KNOWN" || true; } \
  | sed -E 's/.*\.([A-Za-z0-9_]+)\.([a-zA-Z0-9_]+)$/\1.\2/' | sort -u > "$BASE"

NEW="$(comm -23 "$NOW" "$BASE")"
FIXED="$(comm -13 "$NOW" "$BASE")"

echo "  全量 $TOTAL 跑 / $(wc -l < "$NOW" | tr -d ' ') 红（基线 $(wc -l < "$BASE" | tr -d ' ') 条）"

if [ -n "$FIXED" ]; then
  echo ""
  echo "🎉 这几条已经修好了，请从 backend/known-failures.txt 里删掉对应行："
  echo "$FIXED" | sed 's/^/    /'
  echo "  （清单只准变短 —— 不删的话，下次有人把它改回去也没人发现）"
fi

if [ -n "$NEW" ]; then
  echo ""
  echo "✗ 新增了测试失败，这些不在 backend/known-failures.txt 里："
  echo "$NEW" | sed 's/^/    /'
  echo ""
  echo "  完整日志：$LOG"
  echo "  ⚠ 先确认是不是自己引起的：这个脚本跑的是**干净 HEAD 副本**，"
  echo "    共享工作区里别人未提交的改动影响不到它 —— 所以这里红了，"
  echo "    要么是 HEAD 上的真回归，要么是别人已经提交进 HEAD 的问题。"
  echo "    后者请把失败的用例名报给对应会话，别替别人改。"
  exit 1
fi

echo "✓ 没有新增失败（基线 $(wc -l < "$BASE" | tr -d ' ') 条一条没多）"
rm -f "$LOG"   # 通过就不留垃圾；上面每条失败路径都保留它并打印路径

# 记下**这一次验的是哪个提交**，给 deploy-backend.sh 比对用。
#
# 为什么需要：闸门跑四到六分钟，而这个目录常有多个会话在推 —— 跑完之后 HEAD 会前进，
# 而部署脚本建的是**当时的** HEAD。于是「验过的那份」与「发出去的那份」可以不是一份。
# 2026-08-29 真实发生过一次：闸门验 acf0679e，部署建 ea4d428a，中间多了两笔。
# 那次无害（多出来的 backend 改动只是一个基线文本，不进 jar），但那是**人对了一眼**
# 才知道的 —— 而人对一眼是会累的。
#
# 写在 .git/ 下：天然不进版本库，且随仓库走（不会被别的 checkout 串味）。
git rev-parse HEAD > "$(git rev-parse --git-dir)/gate-verified-sha"
