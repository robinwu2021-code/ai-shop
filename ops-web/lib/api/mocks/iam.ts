// 覆盖范围：员工与权限（P-1.1）。
import * as db from "@/lib/mock/db";
import { CRITICAL_PERMS } from "@/lib/permissions";
import { SCOPED_ROLES, type RoleDef, type Staff } from "@/lib/types";
import type { Role } from "@/lib/auth";
import type { IamApi } from "../contracts/iam";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function findStaff(staffNo: string): Staff {
  const s = db.staffs.find((x) => x.staffNo === staffNo);
  if (!s) notFound("员工", "Staff member", staffNo);
  return s;
}
function findRole(role: Role): RoleDef {
  const r = db.roleDefs.find((x) => x.role === role);
  if (!r) notFound("角色", "Role", role);
  return r;
}

/** 写审计。IAM 自身的每一次写操作都要留痕 —— 权限变更查不到人是最糟的情况。 */
function audit(action: string, target: string, detail: string, critical: boolean) {
  db.auditLogs.unshift({
    logNo: db.nextNo("AL", db.auditLogs, 9000, "logNo"),
    at: "2026-08-06T00:00:00Z",
    operator: "admin",
    action, target, detail, critical,
  });
}

export const iamMock: IamApi = {
  listStaffs: (q = {}) =>
    wait(
      db.paginate(db.staffs, q.page, q.size, (s) =>
        db.eqHit(q.role, s.role) &&
        (!q.enabled || (q.enabled === "1") === s.enabled) &&
        db.kwHit(q.keyword, s.staffNo, s.username, s.name),
      ),
    ),

  setStaffEnabled: async (staffNo, enabled) => {
    const s = findStaff(staffNo);
    // 停用最后一个启用中的超管 = 把系统锁死，之后没人能再开权限
    if (!enabled && s.role === "SUPER_ADMIN") {
      const activeAdmins = db.staffs.filter((x) => x.role === "SUPER_ADMIN" && x.enabled).length;
      if (activeAdmins <= 1) fail("不能停用最后一个启用中的超级管理员，系统会失去管理入口", "The last active super admin cannot be disabled — the system would lose its way in");
    }
    s.enabled = enabled;
    audit(enabled ? "启用员工" : "停用员工", `${s.staffNo} ${s.name}`, `角色：${s.role}`, s.role === "SUPER_ADMIN");
    return wait(s, 400);
  },

  setStaffRole: async (staffNo, role) => {
    const s = findStaff(staffNo);
    const from = s.role;
    if (from === "SUPER_ADMIN" && role !== "SUPER_ADMIN") {
      const activeAdmins = db.staffs.filter((x) => x.role === "SUPER_ADMIN" && x.enabled).length;
      if (activeAdmins <= 1) fail("不能降级最后一个超级管理员", "The last super admin cannot be demoted");
    }
    // 换到不需要数据域的角色时清掉旧 scope：留着会显示成"被限制"，实际没有
    if (!SCOPED_ROLES.includes(role)) {
      s.merchantNo = undefined; s.communityNo = undefined; s.pickupNo = undefined;
    }
    s.role = role;
    audit("调整角色", `${s.staffNo} ${s.name}`, `${from} → ${role}`, role === "SUPER_ADMIN");
    return wait(s, 400);
  },

  setStaffScope: async (staffNo, scope) => {
    const s = findStaff(staffNo);
    const hasScope = !!(scope.merchantNo || scope.communityNo || scope.pickupNo);
    // 给全量角色配数据域：界面上看着"被限制到某社区"，实际 can() 全放行 —— 比不配更危险
    if (hasScope && !SCOPED_ROLES.includes(s.role)) {
      fail(`${s.role} 是全量数据角色，配置数据域不会生效，反而会让人误以为它被限制了`, `${s.role} already sees all data — setting a scope changes nothing and only makes it look restricted`);
    }
    s.merchantNo = scope.merchantNo || undefined;
    s.communityNo = scope.communityNo || undefined;
    s.pickupNo = scope.pickupNo || undefined;
    audit("调整数据域", `${s.staffNo} ${s.name}`,
      `商家：${s.merchantNo ?? "无"} · 社区：${s.communityNo ?? "无"} · 自提点：${s.pickupNo ?? "无"}`, false);
    return wait(s, 400);
  },

  listRoles: async () =>
    wait(db.roleDefs.map((r) => ({ ...r, staffCount: db.staffs.filter((s) => s.role === r.role).length }))),

  setRolePerms: async (role, perms) => {
    const r = findRole(role);
    // 内置角色的定义就是"全部"。可编辑意味着能把超管自己降权，然后没人能改回来。
    if (r.builtin) fail("内置角色（超级管理员）的权限不可编辑", "The built-in super admin role's permissions cannot be edited");
    const added = perms.filter((p) => !r.perms.includes(p));
    const critical = added.some((p) => (CRITICAL_PERMS as readonly string[]).includes(p));
    r.perms = [...perms];
    audit("授予角色权限", role, added.length ? `新增：${added.join("、")}` : "调整权限集合", critical);
    return wait(r, 400);
  },

  listAuditLogs: (q = {}) =>
    wait(
      db.paginate(db.auditLogs, q.page, q.size, (l) =>
        (q.critical !== "1" || l.critical) &&
        db.kwHit(q.keyword, l.logNo, l.operator, l.action, l.target, l.detail),
      ),
    ),
};
