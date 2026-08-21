#!/usr/bin/env bash
# 通过腾讯云助手(TAT)把部署公钥推进目标机 authorized_keys —— 免密、不开新端口、不传私钥。
# 前置：tccli configure 配好 SecretId/SecretKey（需 TAT 权限），目标机已装云助手 agent。
# 用法：bash bootstrap-authorized-key.sh [公网IP] [登录用户名]
set -euo pipefail

# 凭据/密钥目录（**故意在仓库外** —— 私钥与 API 密钥永不入库）
TX_ENV_DIR="${TX_ENV_DIR:-$HOME/work/env/tencent}"
KEY_DIR="$TX_ENV_DIR"

# ── 0. 载入凭据（同目录 tencent.env，600）──────────────────────────
ENV_FILE="$KEY_DIR/tencent.env"
if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
else
  echo "✗ 缺少凭据文件：$ENV_FILE（照 tencent.env.example 建并填值）"; exit 1
fi
if [[ -z "${TENCENTCLOUD_SECRET_ID:-}" || -z "${TENCENTCLOUD_SECRET_KEY:-}" ]]; then
  echo "✗ $ENV_FILE 里 TENCENTCLOUD_SECRET_ID / SECRET_KEY 还是空的"; exit 1
fi

HOST_IP="${1:-${TX_HOST_IP:-106.55.27.246}}"
LOGIN_USER="${2:-${TX_LOGIN_USER:-root}}"
PUB_KEY_FILE="$KEY_DIR/soukmind_tx.pub"
# 候选地域：按命中概率排，找到即止（避免在错误 region 上白问一圈）
REGIONS="${TX_REGIONS:-${TENCENTCLOUD_REGION:-ap-guangzhou} ap-guangzhou ap-shanghai ap-beijing ap-chengdu ap-nanjing ap-hongkong}"

command -v tccli >/dev/null || { echo "✗ 未找到 tccli，先 pipx install tccli"; exit 1; }
[[ -f "$PUB_KEY_FILE" ]] || { echo "✗ 公钥不存在：$PUB_KEY_FILE"; exit 1; }
PUB_KEY="$(cat "$PUB_KEY_FILE")"

# ── 1. 定位实例：先 CVM 后 Lighthouse，逐地域找公网 IP ──────────────
INSTANCE_ID=""; REGION=""; PRODUCT=""
for r in $REGIONS; do
  id="$(tccli cvm DescribeInstances --region "$r" \
        --Filters "[{\"Name\":\"public-ip-address\",\"Values\":[\"$HOST_IP\"]}]" \
        2>/dev/null | python3 -c 'import sys,json;d=json.load(sys.stdin);s=d.get("InstanceSet") or [];print(s[0]["InstanceId"] if s else "")' 2>/dev/null || true)"
  if [[ -n "$id" ]]; then INSTANCE_ID="$id"; REGION="$r"; PRODUCT="CVM"; break; fi

  id="$(tccli lighthouse DescribeInstances --region "$r" \
        --Filters "[{\"Name\":\"public-ip-address\",\"Values\":[\"$HOST_IP\"]}]" \
        2>/dev/null | python3 -c 'import sys,json;d=json.load(sys.stdin);s=d.get("InstanceSet") or [];print(s[0]["InstanceId"] if s else "")' 2>/dev/null || true)"
  if [[ -n "$id" ]]; then INSTANCE_ID="$id"; REGION="$r"; PRODUCT="Lighthouse"; break; fi
done

[[ -n "$INSTANCE_ID" ]] || { echo "✗ 在 [$REGIONS] 里没找到公网 IP = $HOST_IP 的实例。用 TX_REGIONS 指定地域重试。"; exit 1; }
echo "✓ 实例：$INSTANCE_ID（$PRODUCT · $REGION）"

# ── 2. 下发命令：幂等追加公钥（已存在则跳过）────────────────────────
HOME_DIR='$([ "'"$LOGIN_USER"'" = root ] && echo /root || echo /home/'"$LOGIN_USER"')'
read -r -d '' REMOTE_SCRIPT <<EOF || true
set -e
HOME_DIR=$HOME_DIR
mkdir -p "\$HOME_DIR/.ssh" && chmod 700 "\$HOME_DIR/.ssh"
touch "\$HOME_DIR/.ssh/authorized_keys" && chmod 600 "\$HOME_DIR/.ssh/authorized_keys"
if grep -qF '$PUB_KEY' "\$HOME_DIR/.ssh/authorized_keys"; then
  echo "SKIP: 公钥已存在"
else
  echo '$PUB_KEY' >> "\$HOME_DIR/.ssh/authorized_keys"
  echo "ADDED: 公钥已追加"
fi
chown -R $LOGIN_USER "\$HOME_DIR/.ssh" 2>/dev/null || true
grep -c . "\$HOME_DIR/.ssh/authorized_keys" | sed 's/^/authorized_keys 行数: /'
EOF

B64="$(printf '%s' "$REMOTE_SCRIPT" | base64 | tr -d '\n')"
INV="$(tccli tat RunCommand --region "$REGION" \
        --InstanceIds "[\"$INSTANCE_ID\"]" \
        --Content "$B64" --CommandType SHELL --Username root \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["InvocationId"])')"
echo "✓ 已下发（InvocationId=$INV），等待执行…"

# ── 3. 轮询结果 ────────────────────────────────────────────────────
for _ in $(seq 1 30); do
  sleep 3
  OUT="$(tccli tat DescribeInvocationTasks --region "$REGION" \
          --Filters "[{\"Name\":\"invocation-id\",\"Values\":[\"$INV\"]}]" --HideOutput False)"
  STATE="$(printf '%s' "$OUT" | python3 -c 'import sys,json;t=json.load(sys.stdin)["InvocationTaskSet"];print(t[0]["TaskStatus"] if t else "")')"
  case "$STATE" in
    SUCCESS)
      printf '%s' "$OUT" | python3 -c 'import sys,json,base64;t=json.load(sys.stdin)["InvocationTaskSet"][0]["TaskResult"];print(base64.b64decode(t["Output"]).decode())'
      echo "✓ 完成。现在验证 SSH："
      echo "  ssh -i $KEY_DIR/soukmind_tx $LOGIN_USER@$HOST_IP 'id'"
      exit 0;;
    FAILED|TIMEOUT|CANCELLED)
      echo "✗ 执行失败（$STATE）："; printf '%s' "$OUT"; exit 1;;
  esac
done
echo "✗ 超时未返回结果，去控制台查 InvocationId=$INV"; exit 1
