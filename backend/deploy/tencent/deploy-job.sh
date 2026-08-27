#!/usr/bin/env bash
# 定时任务调度器发布。与 deploy-backend.sh 同一套路：**新文件名 + 切软链**。
#
# 覆盖正在跑的 jar 会怎样，见 deploy-backend.sh 顶部那段（2026-08-24 线上重现过）。
# 这个进程没有 Ehcache 持久化目录可删，但懒加载类读到新字节一样是 NoClassDefFoundError，
# 而这里的后果更隐蔽：关闭钩子失败 → 在跑的任务留在 job_run.running=1，
# 下次启动看上去像「有任务卡了两小时」。
#
# 用法：./deploy-job.sh [ssh-host]
set -euo pipefail

HOST="${1:-soukmind-tx-root}"
REMOTE_DIR=/opt/ai-shop-job
LOCAL_JAR="$(cd "$(dirname "$0")/../../.." && pwd)/backend/shop-job/target/shop-job-0.1.0-SNAPSHOT.jar"
STAMP="$(date +%Y%m%d-%H%M)"
KEEP=5

[ -f "$LOCAL_JAR" ] || { echo "✗ 找不到 $LOCAL_JAR，先 mvn -DskipTests package -pl shop-job -am" >&2; exit 1; }

echo "→ 上传 shop-job-$STAMP.jar（$(du -h "$LOCAL_JAR" | cut -f1)）"
scp -q "$LOCAL_JAR" "$HOST:$REMOTE_DIR/shop-job-$STAMP.jar"

ssh "$HOST" "set -euo pipefail; cd $REMOTE_DIR
  before=\$(wc -l < /var/log/ai-shop/job.log 2>/dev/null || echo 0)
  ln -sfn shop-job-$STAMP.jar shop-job.jar.new
  mv -Tf shop-job.jar.new shop-job.jar
  systemctl restart ai-shop-job
  # **闸门不能是 systemctl is-active** —— Restart=always 会让一个起来就崩的进程
  # 在重启间隙里显示 activating，看上去挺健康。要等日志里那行「已启动」
  ok=0
  for i in \$(seq 1 30); do
    if tail -n +\$((before+1)) /var/log/ai-shop/job.log 2>/dev/null | grep -q '定时任务调度器已启动'; then ok=1; break; fi
    sleep 2
  done
  if [ \"\$ok\" != 1 ]; then
    echo '✗ 60 秒内没看到启动日志。最后 30 行：' >&2
    tail -n 30 /var/log/ai-shop/job.log >&2 || true
    echo '回滚：ln -sfn <上一个 jar> shop-job.jar && systemctl restart ai-shop-job' >&2
    exit 1
  fi
  echo '✓ 调度器已启动'
  # 闸门二：轮询是否真的够到了业务系统。**起来了 ≠ 调得通** ——
  # 密钥不匹配时进程一切正常，只是每个任务都 401
  sleep 5
  if tail -n +\$((before+1)) /var/log/ai-shop/job.log | grep -q '取任务声明失败'; then
    echo '⚠ 取任务声明失败 —— 查 JOB_TOKEN 是否与业务系统的 shop.job.internal-token 一致' >&2
  fi
  cur=\$(readlink shop-job.jar)
  ls -t shop-job-*.jar 2>/dev/null | tail -n +$((KEEP+1)) | grep -vx \"\$cur\" | xargs -r rm -f
  ls -l shop-job.jar
"
