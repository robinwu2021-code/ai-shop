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
# 用法：
#   scripts/check-head-compiles.sh            # 检查 HEAD
#   scripts/check-head-compiles.sh <commit>   # 检查任意提交
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
