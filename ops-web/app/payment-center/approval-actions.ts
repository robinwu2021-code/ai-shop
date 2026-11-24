// 审核待办的按钮显隐（SY 审批中心）。
// near-pay 详情接口返回 allowedActions，那是服务端按「当前会话 + 最新状态」算的结论：认领后列表里的旧行、
// 或一时取不到员工号（/me/access 超时），都不能把它盖掉 —— 否则服务端明明允许审核，页面上却没有「同意」。
// 服务端没给（列表行菜单）时才退回本地判断。
type Row = { status: string; assigneeNo?: string | null; applicantNo?: string | null; executionStatus?: string | null };

/** 页面动作 → 服务端 allowedActions 里的名字（review 拆成同意 / 驳回两个按钮） */
const SERVER_KEY: Record<string, string> = { claim: "claim", approve: "review", reject: "review", release: "release", assign: "assign", withdraw: "withdraw", execute: "execute" };

export function approvalActionVisible(key: string, r: Row | null | undefined, me: string | null, server: readonly string[] | undefined): boolean {
  if (!r) return false;
  const sk = SERVER_KEY[key];
  if (server && sk) return server.includes(sk);
  const pending = r.status.startsWith("PENDING");
  switch (key) {
    case "claim": return pending && !r.assigneeNo;
    case "approve":
    case "reject":
    case "release": return pending && !!me && r.assigneeNo === me;
    case "assign": return pending;
    case "withdraw": return pending && !!me && r.applicantNo === me;
    case "execute": return r.status === "APPROVED" && r.executionStatus === "NOT_STARTED";
    default: return false;
  }
}
