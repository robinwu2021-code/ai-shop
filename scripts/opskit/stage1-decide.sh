#!/usr/bin/env bash
# 阶段 1 联调：用 aishop 应用凭证代员工审批（TDD-OpsKit审批对接 §3.1 K8）。只用于联调；阶段 2 起页面审批一律用成员令牌。
#
#   OPSKIT_BASE=http://192.168.3.80:8090/api/opskit/v1 \
#   OPSKIT_APP_AISHOP_CREDENTIAL=...（经安全渠道取得，勿写进文件或聊天） \
#   scripts/opskit/stage1-decide.sh <实例号 PI…> <员工号> APPROVE|REJECT [驳回原因]
#
# 本人审批：员工号与送审时的提交人相同即可（默认流程 selfApproval=true）。
set -euo pipefail
instance="${1:?实例号}"; staff="${2:?员工号}"; decision="${3:?APPROVE|REJECT}"; reason="${4:-}"
: "${OPSKIT_BASE:?}"; : "${OPSKIT_APP_AISHOP_CREDENTIAL:?}"
ws="${OPSKIT_WORKSPACE:-ext:PLATFORM}"; perms="${OPSKIT_DECIDE_PERMS:-merchant:apply:audit}"
api() { curl -sS -X POST "$OPSKIT_BASE/workspaces/$ws$1" -H "Authorization: Bearer $OPSKIT_APP_AISHOP_CREDENTIAL" \
          -H 'Content-Type: application/json' -H 'Accept-Language: zh' --data "$2"; }
behalf=$(python3 -c 'import json,sys;print(json.dumps({"externalRef":sys.argv[1],"displayName":sys.argv[1],"perms":sys.argv[2].split(",")}))' "$staff" "$perms")
todos=$(api /todos/query "{\"origin\":\"PROCESS\",\"page\":1,\"size\":200,\"onBehalfOf\":$behalf}")
read -r task approval < <(python3 -c 'import json,sys
d=json.loads(sys.argv[1]); hit=[r for r in d.get("list",[]) if r.get("instanceNo")==sys.argv[2]]
print(hit[0]["taskNo"], hit[0]["approvalNo"]) if hit else sys.exit("待办里没有实例 "+sys.argv[2]+"（员工权限不够，或单子不在审批中）")' "$todos" "$instance")
body=$(python3 -c 'import json,sys;b={"taskNo":sys.argv[1],"decision":sys.argv[2],"onBehalfOf":json.loads(sys.argv[3])}
if sys.argv[4]: b["reason"]=sys.argv[4]
print(json.dumps(b,ensure_ascii=False))' "$task" "$decision" "$behalf" "$reason")
api "/approvals/$approval/decide" "$body" | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("approvalNo"),d.get("status"),d.get("error",""))'
