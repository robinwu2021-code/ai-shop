// 覆盖范围：员工与权限（P-1.1）。
import { client } from "../http-client";
import type { IamApi } from "../contracts/iam";
import type { Staff } from "@/lib/types";
import { toBackendRole, toOpsRole } from "./dashboard";

/**
 * 后端 `StaffVO` 的原样形状。**与 ops-web 的 `Staff` 不同形**：
 * 后端给 `roles`（数组）与 `realName`，前端要 `role`（单个）与 `name`。
 *
 * 不映射的结果实测过：角色列每行显示 `role.undefined`、姓名列整列空白 ——
 * 而接口 200、控制台干净、574 条测试全绿。**契约错配只有在浏览器里才看得见**。
 */
interface BackendStaff {
  staffNo: string;
  username: string;
  realName?: string;
  roles?: string[];
  perms?: string[];
  status?: string;
  merchantNo?: string;
  communityNo?: string;
  pickupNo?: string;
  lastLoginAt?: number;
  mustChangePassword?: boolean;
}

interface StaffPage {
  records: BackendStaff[];
  total: number;
  page: number;
  size: number;
}

/**
 * **2026-08-12：不再只取第一个角色。**
 *
 * 此前这里写 `role: toOpsRole(s.roles?.[0])` —— 后端给数组、前端只认第一个，
 * 于是「这个人有两个角色」这件事在页面上根本表达不出来。
 * 角色码仍要翻译：BD / GOODS_OPS / SUPPORT 是历史遗留的三个异名同义。
 */
function toStaff(s: BackendStaff): Staff {
  return {
    staffNo: s.staffNo,
    username: s.username,
    name: s.realName ?? "",
    roles: (s.roles ?? []).map((r) => toOpsRole(r) as string),
    merchantNo: s.merchantNo || undefined,
    communityNo: s.communityNo || undefined,
    pickupNo: s.pickupNo || undefined,
    enabled: s.status !== "DISABLED",
    mustChangePassword: s.mustChangePassword === true,
    lastLoginAt: s.lastLoginAt ? new Date(s.lastLoginAt).toISOString() : undefined,
    createdAt: "",
  };
}

export const iamHttp: IamApi = {
  listStaffs: async (q) => {
    /*
     * **角色筛选要先把 ops-web 角色码翻回后端码**。BD / GOODS_OPS / SUPPORT
     * 三个是历史异名同义 —— 筛选框选的是 ops-web 那份（如 PRODUCT_OPS），
     * 库里的 sys_ops_staff.roles 存的是后端那份（GOODS_OPS）。
     * 不翻译的话请求 200、返回 0 条，界面上跟这个角色没人一样，
     * 而实际上是查询词一开始就没对上。
     */
    const p = await client.get<StaffPage>("/ops/staffs", { ...q, role: toBackendRole(q?.role) });
    return { ...p, records: (p?.records ?? []).map(toStaff) };
  },
  setStaffEnabled: async (no, enabled) =>
    toStaff(await client.post<BackendStaff>(`/ops/staffs/${no}/enabled`, { enabled })),
  createStaff: async (username, realName, roles) => {
    const r = await client.post<{ staff: BackendStaff; initialPassword: string }>(
      "/ops/staffs", { username, realName, roles });
    return { staff: toStaff(r.staff), initialPassword: r.initialPassword };
  },
  setStaffRoles: async (no, roles) =>
    toStaff(await client.post<BackendStaff>(`/ops/staffs/${no}/roles`, { roles })),
  setStaffScope: async (no, scope) =>
    toStaff(await client.post<BackendStaff>(`/ops/staffs/${no}/scope`, scope)),
  // 角色与功能点。**路径是 /ops/perm/** 而不是 /ops/roles** ——
  // 后端从来没有过 /ops/roles，这一整块此前点下去 404
  listRoles: () => client.get("/ops/perm/roles", { end: "OPS" }),
  listPermFunctions: () => client.get("/ops/perm/functions", { end: "OPS" }),
  movePermFunction: (functionCode: string, direction: "UP" | "DOWN") =>
    client.post(`/ops/perm/functions/${encodeURIComponent(functionCode)}/move`, { direction }),
  movePermPoint: (pointCode: string, direction: "UP" | "DOWN") =>
    client.post(`/ops/perm/points/${encodeURIComponent(pointCode)}/move`, { direction }),
  reorderPermFunctions: (codes: string[]) =>
    client.post("/ops/perm/functions/reorder", { codes }),
  reorderPermPoints: (functionCode: string, codes: string[]) =>
    client.post("/ops/perm/points/reorder", { functionCode, codes }),
  getRolePoints: (roleCode) => client.get(`/ops/perm/roles/${roleCode}/points`),
  setRolePoints: (roleCode, pointCodes) =>
    client.post(`/ops/perm/roles/${roleCode}/points`, { pointCodes }),
  createRole: (roleCode, name) => client.post("/ops/perm/roles", { roleCode, name }),
  renameRole: (roleCode, name) => client.post(`/ops/perm/roles/${roleCode}/rename`, { name }),
  removeRole: (roleCode) => client.post(`/ops/perm/roles/${roleCode}/delete`, {}),
  forceLogoutRole: (roleCode) => client.post(`/ops/perm/roles/${roleCode}/force-logout`, {}),
  /**
   * 审计日志。**2026-08-12 修复路径**：此前调的是 `/ops/audit-logs`（复数），
   * 真实路径是 `/ops/audit-log`（单数）。
   *
   * **2026-08-12 补详情**：`sys_audit_log` 加了 `ip/client_type/critical/before_json/after_json`
   * 五列，后端改真分页 + `critical`/`keyword` 过滤（`OpsPlatformController.auditLogs`）。
   * `before`/`after` 只有员工与权限域的部分动作（STAFF_ROLES/STAFF_ENABLED/PERM_ROLE_*）
   * 写了结构化快照，其余调用点仍是空——前端按有没有值显示，不伪造。
   */
  listAuditLogs: async (q) => {
    const critical = q?.critical === "1" ? true : q?.critical === "0" ? false : undefined;
    const p = await client.get<BackendAuditLogPage>("/ops/audit-log", {
      target: q?.target,
      keyword: q?.keyword,
      critical,
      page: q?.page ?? 1,
      size: q?.size ?? 10,
    });
    return {
      records: p.records.map((l) => ({
        logNo: String(l.logNo),
        at: new Date(l.at).toISOString(),
        operator: `${l.staffName}（${l.staffNo}）`,
        action: l.action,
        target: l.target,
        detail: l.detail,
        critical: l.critical,
        ip: l.ip ?? undefined,
        clientType: l.clientType ?? undefined,
        before: l.before ?? undefined,
        after: l.after ?? undefined,
      })),
      total: p.total,
      page: p.page,
      size: p.size,
    };
  },
};

interface BackendAuditLog {
  logNo: number;
  staffNo: string;
  staffName: string;
  action: string;
  target: string;
  detail: string;
  at: number;
  ip: string | null;
  clientType: string | null;
  critical: boolean;
  before: string | null;
  after: string | null;
}

interface BackendAuditLogPage {
  records: BackendAuditLog[];
  total: number;
  page: number;
  size: number;
}
