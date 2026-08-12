// 覆盖范围：员工与权限（P-1.1）。
import * as db from "@/lib/mock/db";
import { CRITICAL_PERMS } from "@/lib/permissions";
import { SCOPED_ROLES, type RoleDef, type Staff } from "@/lib/types";
import type { Role } from "@/lib/auth";
import type { IamApi } from "../contracts/iam";
import { fail, notFound } from "@/lib/biz-error";
import { NAV } from "@/lib/nav";
import { UI_PERM_MAP, UNIMPLEMENTED } from "@/lib/perm-map";
import { wait } from "./_wait";

function findStaff(staffNo: string): Staff {
  const s = db.staffs.find((x) => x.staffNo === staffNo);
  if (!s) notFound("员工", "Staff member", staffNo);
  return s;
}
function findRole(roleCode: string): RoleDef {
  const r = db.roleDefs.find((x) => x.roleCode === roleCode);
  if (!r) notFound("角色", "Role", roleCode);
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
    wait(db.roleDefs.map((r) => ({
      ...r,
      pointCount: (db.rolePoints[r.roleCode] ?? []).length,
      staffCount: db.staffs.filter((s) => s.role === r.roleCode).length,
    }))),

  /*
   * 功能点全集。mock 从 NAV 派生 —— 与真库同源（那份种子也是从 nav.ts 生成的）。
   * 后端没有的码标 NOT_IMPLEMENTED，**与真接口同一口径**：
   * mock 比后端好看，页面就会在 mock 下看着正常、连上真后端才发现是空的。
   */
  listPermFunctions: async () =>
    wait(NAV.map((sec, si) => ({
      functionCode: `OPS_${sec.key.toUpperCase()}`,
      name: sec.label,
      icon: sec.icon,
      href: sec.href,
      sort: (si + 1) * 10,
      points: (sec.children ?? []).map((l, li) => ({
        pointCode: l.perm!,
        name: l.label,
        groupName: l.group ?? null,
        href: l.href,
        uiPermCode: l.perm ?? null,
        permCode: UI_PERM_MAP[l.perm!] === UNIMPLEMENTED ? null : l.perm!,
        backendStatus: UI_PERM_MAP[l.perm!] === UNIMPLEMENTED ? "NOT_IMPLEMENTED" : "IMPLEMENTED",
        uiReady: l.ready === true,
        matrixCode: l.matrix ?? null,
        pointType: "MENU",
        sort: (li + 1) * 10,
      })),
    }))),

  getRolePoints: async (roleCode) => wait([...(db.rolePoints[roleCode] ?? [])]),

  setRolePoints: async (roleCode, pointCodes) => {
    const r = findRole(roleCode);
    /*
     * **预置角色拒绝修改** —— 与后端 10440 同一条闸。
     * 它们是 Perms.java 的镜像，改了会与回落表分叉，而什么时候回落不由我们决定。
     */
    if (r.builtin) {
      fail("预置角色不可修改", "Built-in roles cannot be modified");
    }
    const before = db.rolePoints[roleCode] ?? [];
    const added = pointCodes.filter((p) => !before.includes(p));
    const critical = added.some((p) => (CRITICAL_PERMS as readonly string[]).includes(p));
    db.rolePoints[roleCode] = [...pointCodes];
    audit("授予角色功能点", roleCode,
      added.length ? `新增：${added.join("、")}` : "调整功能点集合", critical);
    return wait({ ...r, pointCount: pointCodes.length }, 400);
  },

  createRole: async (roleCode, name) => {
    if (db.roleDefs.some((x) => x.roleCode === roleCode)) {
      fail("角色码已存在", "Role code already exists");
    }
    const r: RoleDef = { roleCode, name, endCode: "OPS", builtin: false, pointCount: 0, staffCount: 0 };
    db.roleDefs.push(r);
    db.rolePoints[roleCode] = [];
    audit("新建角色", roleCode, name, false);
    return wait(r, 400);
  },

  removeRole: async (roleCode) => {
    const r = findRole(roleCode);
    if (r.builtin) fail("预置角色不可删除", "Built-in roles cannot be deleted");
    // **还有人在用就不让删** —— 删了他们能登录但什么都点不动，且看不出原因
    if (db.staffs.some((s) => s.role === roleCode)) {
      fail("还有账号在用这个角色", "Role is still assigned to staff");
    }
    db.roleDefs.splice(db.roleDefs.indexOf(r), 1);
    delete db.rolePoints[roleCode];
    audit("删除角色", roleCode, r.name, true);
    return wait(undefined, 400);
  },

  listAuditLogs: (q = {}) =>
    wait(
      db.paginate(db.auditLogs, q.page, q.size, (l) =>
        (q.critical !== "1" || l.critical) &&
        db.kwHit(q.keyword, l.logNo, l.operator, l.action, l.target, l.detail),
      ),
    ),
};
