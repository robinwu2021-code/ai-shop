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
        // 多角色：**持有任一即命中** —— 筛「客服」要能筛出「客服 + 风控」那个人
        (!q.role || s.roles.includes(q.role)) &&
        (!q.enabled || (q.enabled === "1") === s.enabled) &&
        db.kwHit(q.keyword, s.staffNo, s.username, s.name),
      ),
    ),

  setStaffEnabled: async (staffNo, enabled) => {
    const s = findStaff(staffNo);
    // 停用最后一个启用中的超管 = 把系统锁死，之后没人能再开权限
    if (!enabled && s.roles.includes("SUPER_ADMIN")) {
      const activeAdmins = db.staffs.filter((x) => x.roles.includes("SUPER_ADMIN") && x.enabled).length;
      if (activeAdmins <= 1) fail("不能停用最后一个启用中的超级管理员，系统会失去管理入口", "The last active super admin cannot be disabled — the system would lose its way in");
    }
    s.enabled = enabled;
    audit(enabled ? "启用员工" : "停用员工", `${s.staffNo} ${s.name}`,
      `角色：${s.roles.join("、")}`, s.roles.includes("SUPER_ADMIN"));
    return wait(s, 400);
  },

  createStaff: async (username, realName, roles) => {
    if (db.staffs.some((x) => x.username === username)) {
      fail("登录名已被占用", "Username is already taken");
    }
    const staff: Staff = {
      staffNo: `E${9000 + db.staffs.length + 1}`,
      username, name: realName, roles: [...roles], enabled: true,
      mustChangePassword: true, createdAt: new Date().toISOString(),
    };
    db.staffs.push(staff);
    audit("新建员工", `${staff.staffNo} ${realName}`, `角色：${roles.join("、")}`, false);
    /*
     * mock 也返回一个**假的一次性密码** —— 不返回的话，
     * 「关掉抽屉就再也取不到」这条交互在 mock 下走不通，
     * 而那正是这个抽屉最需要试对的一处。
     */
    return wait({ staff, initialPassword: "MockPwd" + (1000 + db.staffs.length) }, 400);
  },

  setStaffRoles: async (staffNo, roles) => {
    const s = findStaff(staffNo);
    const from = s.roles.join("、");
    if (roles.length === 0) {
      // 空角色 = 能登录但什么都点不动，且界面上看不出原因。要停用请用 enabled
      fail("至少要有一个角色", "At least one role is required");
    }
    if (s.roles.includes("SUPER_ADMIN") && !roles.includes("SUPER_ADMIN")) {
      const activeAdmins = db.staffs.filter((x) => x.roles.includes("SUPER_ADMIN") && x.enabled).length;
      if (activeAdmins <= 1) fail("不能降级最后一个超级管理员", "The last super admin cannot be demoted");
    }
    /*
     * 数据域：**持有任一受限角色就保留 scope**。
     * 「全部角色都受限才算受限」的话，给社区运营再加一个全量角色
     * 会悄悄把他的社区限制变成摆设 —— 而界面上那一栏还写着社区号。
     */
    if (!roles.some((r) => SCOPED_ROLES.includes(r as Role))) {
      s.merchantNo = undefined; s.communityNo = undefined; s.pickupNo = undefined;
    }
    s.roles = [...roles];
    audit("调整角色", `${s.staffNo} ${s.name}`, `${from} → ${roles.join("、")}`,
      roles.includes("SUPER_ADMIN"));
    return wait(s, 400);
  },

  setStaffScope: async (staffNo, scope) => {
    const s = findStaff(staffNo);
    const hasScope = !!(scope.merchantNo || scope.communityNo || scope.pickupNo);
    // 给全量角色配数据域：界面上看着"被限制到某社区"，实际 can() 全放行 —— 比不配更危险
    if (hasScope && !s.roles.some((r) => SCOPED_ROLES.includes(r as Role))) {
      const label = s.roles.join("、");
      fail(`${label} 是全量数据角色，配置数据域不会生效，反而会让人误以为它被限制了`, `${label} already sees all data — setting a scope changes nothing and only makes it look restricted`);
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
      staffCount: db.staffs.filter((s) => s.roles.includes(r.roleCode)).length,
    }))),

  /*
   * 功能点全集。mock 从 NAV 派生 —— 与真库同源（那份种子也是从 nav.ts 生成的）。
   * 后端没有的码标 NOT_IMPLEMENTED，**与真接口同一口径**：
   * mock 比后端好看，页面就会在 mock 下看着正常、连上真后端才发现是空的。
   */
  /*
   * 调序在 mock 下**只等待、不改数据**。
   *
   * 真要在 mock 里实现，就得给 NAV 派生出来的那份加一层可变 sort ——
   * 而 mock 的功能点是每次从 NAV 现算的，改了也留不住，
   * 做成"点了有反应但刷新就回去"比不做更误导。
   * 排序是库驱动的能力，验证它必须连真后端。
   */
  movePermFunction: async () => wait(undefined as unknown as void),
  movePermPoint: async () => wait(undefined as unknown as void),
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

  renameRole: async (roleCode, name) => {
    const r = findRole(roleCode);
    if (r.builtin) fail("预置角色不可修改", "Built-in roles cannot be modified");
    const before = r.name;
    r.name = name;
    audit("角色改名", roleCode, `${before} → ${name}`, false);
    return wait(r, 400);
  },

  removeRole: async (roleCode) => {
    const r = findRole(roleCode);
    if (r.builtin) fail("预置角色不可删除", "Built-in roles cannot be deleted");
    // **还有人在用就不让删** —— 删了他们能登录但什么都点不动，且看不出原因
    if (db.staffs.some((s) => s.roles.includes(roleCode))) {
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
