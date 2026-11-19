#!/usr/bin/env bash
# UME 渠道密钥一键迁移（用户在本机执行一次，全程无需输入）。
#
#   bash deploy/ume/sync-check/run_keys_all.sh
#
# 重跑安全：已启用并绑定凭据的账户直接记 DONE 跳过（不读 UME、不轮换）；需要轮换设 UME_KEYS_ROTATE=1。
# 依次：备份（ume-db61 上从 .60 每日全库备份抽 msm_mms 两张表 → nearpay_prod.stg_ume_*）→ 列名 → check
#       → roger 试点 apply → 全量 apply → 删除明文备份表。出现 AMBIGUOUS / MISMATCH / INVALID / FAILED 就停，备份表保留待查；
#       MISSING（备份日之后 UME 才新增的密钥）跳过不导，报告里列出。
# near-pay mysql9 root 口令由脚本从 ~/work/env/ume/nearpay-prod/mysql9-root.env 读取，只在进程内传递，不打印。
# 报告只有账户号、渠道商户号、步骤与结论（$OUT/*）。
set -euo pipefail
cd "$(dirname "$0")/../../.."

S=${UME_SSH_CONFIG:-/private/tmp/claude-501/-Users-workingowl-work-ai-shop/077cfac5-18de-42a5-a383-f985ccb619eb/scratchpad/ume-ssh.config}
PY=${UME_PY:-$HOME/.venvs/ume-keys/bin/python}
ROOT_ENV=${UME_NP_ROOT_ENV:-$HOME/work/env/ume/nearpay-prod/mysql9-root.env}
T=docs/handoff/ume-sync-2026-11-19/credential-targets.tsv
OUT=${UME_KEYS_OUT:-$HOME/work/env/ume/keys-run-$(date +%Y%m%d%H%M)}
PILOT=PM0E0F6CC8F7454D75
BAD='AMBIGUOUS|MISMATCH|INVALID|FAILED'
ssh_db() { ssh -F "$S" -o LogLevel=ERROR ume-db61 "$@"; }

NP_PWD=$(grep '^MYSQL9_ROOT_PASSWORD=' "$ROOT_ENV" | cut -d= -f2- | sed "s/^['\"]//; s/['\"]$//")
[[ -n "$NP_PWD" ]] || { echo "读不到 $ROOT_ENV 里的 MYSQL9_ROOT_PASSWORD"; exit 1; }
mkdir -p "$OUT" && chmod 700 "$OUT"

echo "== 1/6 备份到 near-pay 库（从 .60 全库备份抽表）"
scp -q -F "$S" -o LogLevel=ERROR deploy/ume/sync-check/stage_keys.sh ume-db61:/tmp/stage_keys.sh
printf '%s\n' "$NP_PWD" | ssh_db 'bash /tmp/stage_keys.sh --from-backup' | tee "$OUT/stage.txt"

lsof -iTCP:23307 -sTCP:LISTEN >/dev/null 2>&1 || ssh -F "$S" -o LogLevel=ERROR -f -N -o ExitOnForwardFailure=yes -L 23307:127.0.0.1:3307 ume-db61 </dev/null >/dev/null 2>&1
export UME_DB_HOST=127.0.0.1 UME_DB_PORT=23307 UME_DB_USER=root UME_DB_PASSWORD="$NP_PWD"
export UME_COLUMNS=deploy/ume/sync-check/ume-columns.stage.json
export AUTOMATION_KEY=${AUTOMATION_KEY:-$HOME/work/env/ume/automation-ume-ed25519.pem}
export UME_KEYS_ANON=${UME_KEYS_ANON:-0}   # 166 已关闭免认证（/oms-api/pay-ops/v2 对公网可达），默认用运营令牌调用
run() { "$PY" -I deploy/ume/sync-check/credentials.py "$@"; }
verdicts() { grep -E '^SUMMARY' "$1"; }

echo "== 2/6 列名"
run describe | tee "$OUT/describe.txt"

echo "== 3/6 check（只读）"
run check "$T" --out "$OUT/check.json" > "$OUT/check.log" || true
verdicts "$OUT/check.log"
if grep -qE " ($BAD) " "$OUT/check.log" || ! grep -q '"FOUND"' <(verdicts "$OUT/check.log"); then
  grep -E " ($BAD) " "$OUT/check.log" | head -20
  echo "check 未通过，停在这里（备份表保留）。把 $OUT/describe.txt 与 check.log 发给 Claude。"; exit 1
fi

echo "== 4/6 试点 roger（$PILOT）"
run apply "$T" --only "$PILOT" --out "$OUT/pilot.json" > "$OUT/pilot.log" || true
cat "$OUT/pilot.log"
if grep -qE " ($BAD) " "$OUT/pilot.log"; then echo "试点失败，停在这里。把 $OUT/pilot.log 发给 Claude。"; exit 1; fi

echo "== 5/6 全量"
run apply "$T" --out "$OUT/apply.json" > "$OUT/apply.log" || true
verdicts "$OUT/apply.log"
if grep -qE " ($BAD) " "$OUT/apply.log"; then
  grep -E " ($BAD) " "$OUT/apply.log" | head -20
  echo "有失败，备份表保留。把 $OUT/apply.log 发给 Claude。"; exit 1
fi
grep -E " MISSING " "$OUT/apply.log" > "$OUT/missing.txt" || true
echo "UME 备份里查不到密钥、本次跳过：$(wc -l < "$OUT/missing.txt") 户（$OUT/missing.txt）"

echo "== 6/6 删除明文备份表"
printf '%s\n' "$NP_PWD" | ssh_db 'bash /tmp/stage_keys.sh --drop; rm -f /tmp/stage_keys.sh'
unset NP_PWD UME_DB_PASSWORD
echo "完成。报告目录：$OUT"
