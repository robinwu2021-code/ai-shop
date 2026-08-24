#!/usr/bin/env bash
# 后端发布：**新 jar 用新文件名，再切软链**，不覆盖正在跑的那个。
#
# 为什么不能直接 scp 覆盖 /opt/ai-shop/shop-app.jar：
#
#   Spring Boot fat jar 是懒加载的 —— 有些类**只在关闭时才第一次加载**
#   （Ehcache 的 TieredStore$Provider$1、Lettuce 的 netty executor、
#   Tomcat 的 Lifecycle$SingleUse）。覆盖写改的是同一个 inode 的内容，
#   于是旧 JVM 在关闭过程中去读自己的 jar，读到的是新文件的字节 →
#   NoClassDefFoundError → 那几个 bean 的 close() 全部失败。
#
#   代价不是日志里几行 WARN，而是**所有人掉线**：Ehcache 关不干净，
#   下次启动判定磁盘状态不可信，直接删掉整个持久化目录（日志里只有一行
#   "Probably unclean shutdown was done, so deleted root directory"），
#   而 token 就存在那里。2026-08-24 线上重现过一次。
#
# 新文件名 + 切软链就没有这个问题：软链换指向不影响已打开的 fd，
# 旧 JVM 一路读着自己那个 inode 关完。
#
# 用法：./deploy-backend.sh [ssh-host]     默认 soukmind-tx-root
set -euo pipefail

HOST="${1:-soukmind-tx-root}"
REMOTE_DIR=/opt/ai-shop
LOCAL_JAR="$(cd "$(dirname "$0")/../../.." && pwd)/backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar"
STAMP="$(date +%Y%m%d-%H%M)"
KEEP=5   # 保留最近几个实体 jar。**至少 2** —— 上一个还要给正在关闭的旧 JVM 读

[ -f "$LOCAL_JAR" ] || { echo "✗ 找不到 $LOCAL_JAR，先 mvn -DskipTests package -pl shop-app -am" >&2; exit 1; }

echo "→ 上传 shop-app-$STAMP.jar（$(du -h "$LOCAL_JAR" | cut -f1)）"
scp -q "$LOCAL_JAR" "$HOST:$REMOTE_DIR/shop-app-$STAMP.jar"

ssh "$HOST" "set -euo pipefail; cd $REMOTE_DIR
  # **先记下日志行数**：下面那条 unclean 检查只许看本次重启之后的行。
  # 用 tail -N 是错的 —— 窗口里会残留几小时前那次真的 unclean，
  # 于是每次部署都报警，报到第三次就没人看了。
  before=\$(wc -l < /var/log/ai-shop/app.log 2>/dev/null || echo 0)
  # 原子切换：先建临时软链再 mv -T，避免中间出现「软链不存在」的窗口
  ln -sfn shop-app-$STAMP.jar shop-app.jar.new
  mv -Tf shop-app.jar.new shop-app.jar
  systemctl restart ai-shop
  for i in \$(seq 1 40); do
    [ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8081/actuator/health)\" = 200 ] && break
    sleep 3
  done
  # 闸门一：健康
  code=\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/actuator/health)
  echo \"health=\$code\"
  [ \"\$code\" = 200 ] || { echo '✗ 起不来，回滚：ln -sfn <上一个 jar> shop-app.jar && systemctl restart ai-shop' >&2; exit 1; }
  # 闸门二：**这次关闭是否干净** —— 不干净就意味着所有人的会话又被清了一遍
  if tail -n +\$((before+1)) /var/log/ai-shop/app.log | grep -q 'deleted root directory'; then
    echo '⚠ Ehcache 判定 unclean shutdown 并删了持久化目录 —— 所有人已掉线。查上一次关闭的 NoClassDefFoundError' >&2
  else
    echo '✓ 会话持久化目录保留（clean shutdown）'
  fi
  # 只留最近 \$KEEP 个实体 jar；软链当前指向的那个一定保住
  cur=\$(readlink shop-app.jar)
  ls -t shop-app-*.jar 2>/dev/null | tail -n +$((KEEP+1)) | grep -vx \"\$cur\" | xargs -r rm -f
  ls -l shop-app.jar; ls -t shop-app-*.jar | head -3
"
