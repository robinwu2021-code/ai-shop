#!/usr/bin/env bash
# UME 渠道密钥一键迁移（用户在本机执行一次；口令只在开头输入一次，其余全自动）。
#
#   bash deploy/ume/sync-check/run_keys_all.sh
#
# 依次：备份（ume-db61 上 UME 两张表 → nearpay_prod.stg_ume_*）→ 核对列名 → check（逐户唯一、格式合法）
#       → roger 试点 apply → 全量 apply → 删除明文备份表。任何一步不全绿就停，备份表保留待查。
# 口令、密钥都不打印；报告只有账户号、渠道商户号、步骤与结论（$OUT/*.json）。
set -euo pipefail
cd "$(dirname "$0")/../../.."

S=${UME_SSH_CONFIG:-/private/tmp/claude-501/-Users-workingowl-work-ai-shop/077cfac5-18de-42a5-a383-f985ccb619eb/scratchpad/ume-ssh.config}
PY=${UME_PY:-$HOME/.venvs/ume-keys/bin/python}
T=docs/handoff/ume-sync-2026-11-19/credential-targets.tsv
OUT=${UME_KEYS_OUT:-$HOME/work/env/ume/keys-run-$(date +%Y%m%d%H%M)}
PILOT=PM0E0F6CC8F7454D75
ssh_db() { ssh -F "$S" -o LogLevel=ERROR ume-db61 "$@"; }

read -r -p "UME msm_mms 账号（只读）: " UME_USER
read -r -s -p "UME 口令: " UME_PWD; echo
read -r -s -p "near-pay mysql9 root 口令: " NP_PWD; echo
mkdir -p "$OUT" && chmod 700 "$OUT"

echo "== 1/6 备份到 near-pay 库"
scp -q -F "$S" -o LogLevel=ERROR deploy/ume/sync-check/stage_keys.sh ume-db61:/tmp/stage_keys.sh
printf '%s\n%s\n%s\n' "$UME_USER" "$UME_PWD" "$NP_PWD" | ssh_db 'bash /tmp/stage_keys.sh' | tee "$OUT/stage.txt"
unset UME_PWD

lsof -iTCP:23307 -sTCP:LISTEN >/dev/null 2>&1 || ssh -F "$S" -o LogLevel=ERROR -f -N -o ExitOnForwardFailure=yes -L 23307:127.0.0.1:3307 ume-db61 </dev/null >/dev/null 2>&1
export UME_DB_HOST=127.0.0.1 UME_DB_PORT=23307 UME_DB_USER=root UME_DB_PASSWORD="$NP_PWD"
export UME_COLUMNS=deploy/ume/sync-check/ume-columns.stage.json
export AUTOMATION_KEY=${AUTOMATION_KEY:-$HOME/work/env/ume/automation-ume-ed25519.pem}
run() { "$PY" -I deploy/ume/sync-check/credentials.py "$@"; }

echo "== 2/6 列名"
run describe | tee "$OUT/describe.txt"

echo "== 3/6 check（只读）"
if ! run check "$T" --out "$OUT/check.json" > "$OUT/check.log"; then
  tail -1 "$OUT/check.log"; grep -vE " FOUND " "$OUT/check.log" | head -20
  echo "check 不全是 FOUND，停在这里。把 $OUT/describe.txt 与 check.log 发给 Claude。"; exit 1
fi
tail -1 "$OUT/check.log"

echo "== 4/6 试点 roger（$PILOT）"
run apply "$T" --only "$PILOT" --out "$OUT/pilot.json" | tee "$OUT/pilot.log"

echo "== 5/6 全量"
if ! run apply "$T" --out "$OUT/apply.json" > "$OUT/apply.log"; then
  tail -1 "$OUT/apply.log"; grep -E " FAILED " "$OUT/apply.log" | head -20
  echo "有失败，备份表保留。把 $OUT/apply.log 发给 Claude。"; exit 1
fi
tail -1 "$OUT/apply.log"

echo "== 6/6 删除明文备份表"
printf '%s\n%s\n%s\n' "-" "-" "$NP_PWD" | ssh_db 'bash /tmp/stage_keys.sh --drop; rm -f /tmp/stage_keys.sh'
unset NP_PWD UME_DB_PASSWORD
echo "完成。报告目录：$OUT"
