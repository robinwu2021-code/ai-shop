// 员工与权限 mock（P-1.1）。角色定义从 lib/permissions.ts 的编译期常量派生 ——
// 两处各写一份的话，页面显示的权限和 can() 判的权限会不一样，那比不做还糟。
import { ROLE_LABEL, permsOf } from "@/lib/permissions";
import type { Role } from "@/lib/auth";
import type { AuditLog, RoleDef, Staff } from "@/lib/types";

export const staffs: Staff[] = [
  { staffNo: "E9001", username: "admin", name: "陈平台", role: "SUPER_ADMIN", enabled: true, lastLoginAt: "2026-08-06T00:10:00Z", createdAt: "2026-01-05T02:00:00Z" },
  { staffNo: "E9002", username: "admin2", name: "周超管", role: "SUPER_ADMIN", enabled: true, lastLoginAt: "2026-08-04T09:00:00Z", createdAt: "2026-02-11T02:00:00Z" },
  { staffNo: "E9003", username: "ops.community1", name: "林社区", role: "COMMUNITY_OPS", communityNo: "C001", enabled: true, lastLoginAt: "2026-08-05T23:40:00Z", createdAt: "2026-03-02T02:00:00Z" },
  { staffNo: "E9004", username: "bd.wang", name: "王拓展", role: "MERCHANT_BD", merchantNo: "M903", enabled: true, lastLoginAt: "2026-08-05T10:00:00Z", createdAt: "2026-03-20T02:00:00Z" },
  { staffNo: "E9005", username: "finance01", name: "李财务", role: "FINANCE", enabled: true, lastLoginAt: "2026-08-05T08:30:00Z", createdAt: "2026-02-01T02:00:00Z" },
  { staffNo: "E9006", username: "cs02", name: "赵客服", role: "CS", enabled: true, lastLoginAt: "2026-08-06T01:00:00Z", createdAt: "2026-04-15T02:00:00Z" },
  { staffNo: "E9007", username: "audit01", name: "孙审核", role: "AUDITOR", enabled: true, lastLoginAt: "2026-08-05T14:00:00Z", createdAt: "2026-05-06T02:00:00Z" },
  { staffNo: "E9008", username: "risk01", name: "钱风控", role: "RISK", enabled: false, lastLoginAt: "2026-06-30T02:00:00Z", createdAt: "2026-03-08T02:00:00Z" },
];

/** 角色定义：权限集合直接取自 permissions.ts，避免两份真相。 */
export const roleDefs: RoleDef[] = (Object.keys(ROLE_LABEL) as Role[]).map((role) => ({
  role,
  label: ROLE_LABEL[role],
  builtin: role === "SUPER_ADMIN",
  perms: [...permsOf(role)],
  staffCount: staffs.filter((s) => s.role === role).length,
}));

export const auditLogs: AuditLog[] = [
  { logNo: "AL9001", at: "2026-08-05T02:10:00Z", operator: "admin", action: "授予角色权限", target: "RISK", detail: "新增 risk:blacklist:update（高危）", critical: true },
  { logNo: "AL9002", at: "2026-08-04T07:20:00Z", operator: "admin", action: "调整数据域", target: "E9003 林社区", detail: "communityNo：无 → C001", critical: false },
  { logNo: "AL9003", at: "2026-08-03T03:00:00Z", operator: "admin", action: "停用员工", target: "E9008 钱风控", detail: "离职交接，暂停账号", critical: true },
  { logNo: "AL9004", at: "2026-08-01T06:00:00Z", operator: "admin2", action: "新建员工", target: "E9007 孙审核", detail: "角色：审核员", critical: false },
];
