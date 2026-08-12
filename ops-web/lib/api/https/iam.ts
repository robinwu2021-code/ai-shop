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
  getRolePoints: (roleCode) => client.get(`/ops/perm/roles/${roleCode}/points`),
  setRolePoints: (roleCode, pointCodes) =>
    client.post(`/ops/perm/roles/${roleCode}/points`, { pointCodes }),
  createRole: (roleCode, name) => client.post("/ops/perm/roles", { roleCode, name }),
  renameRole: (roleCode, name) => client.post(`/ops/perm/roles/${roleCode}/rename`, { name }),
  removeRole: (roleCode) => client.post(`/ops/perm/roles/${roleCode}/delete`, {}),
  /**
   * 审计日志。**2026-08-12 修复**：此前调的是 `/ops/audit-logs`（复数），
   * 后端从来没有过这个路径，真实路径是 `/ops/audit-log`（单数）——
   * `ops-endpoint-exists.test.ts` 把它错归进了 `UNBUILT_DOMAINS`（当成"风控域没做"的一部分），
   * 而它其实一直是活的、有真实数据，只是路径和口径都没对上。
   *
   * 后端现状（`OpsPlatformController.auditLogs`）：只接受 `target` 精确匹配，
   * 固定 `limit 200`，返回裸数组，**没有 `logNo` 也没有 `critical` 字段**
   * （`sys_audit_log` 表里根本没有存这两样）。
   *
   * 与其编一个假的 `critical`（比如按 action 字符串猜"像不像高危操作"）——
   * 那样"只看高危"筛选会安静地返回错的结果，比没有这个功能更危险。
   * 这里诚实地：真实的 200 条数据 + 关键词 + 分页都在客户端做（数据集小，
   * 服务端本来就有 200 条硬顶），`critical` 恒为 false，UI 侧同时把
   * "只看高危"筛选项藏起来（见 iam/page.tsx）。完整修复（补 critical 列、
   * logNo、服务端分页/关键词）已登记为独立任务，需要先过数据库→API→对象
   * 对齐，不在这次顺手改的范围内。
   */
  listAuditLogs: async (q) => {
    const all = await client.get<BackendAuditLog[]>("/ops/audit-log", q?.target ? { target: q.target } : undefined);
    const kw = (q?.keyword ?? "").trim().toLowerCase();
    const filtered = kw
      ? all.filter((l) =>
          l.staffNo.toLowerCase().includes(kw) ||
          l.staffName.toLowerCase().includes(kw) ||
          l.action.toLowerCase().includes(kw) ||
          l.target.toLowerCase().includes(kw) ||
          l.detail.toLowerCase().includes(kw))
      : all;
    const page = q?.page ?? 1;
    const size = q?.size ?? 10;
    const start = (page - 1) * size;
    return {
      records: filtered.slice(start, start + size).map((l, i) => ({
        // 后端没有 logNo，合成一个仅用于 React rowKey 的值，不代表真实单号
        logNo: `${l.at}-${start + i}`,
        at: new Date(l.at).toISOString(),
        operator: `${l.staffName}（${l.staffNo}）`,
        action: l.action,
        target: l.target,
        detail: l.detail,
        critical: false,
      })),
      total: filtered.length,
      page,
      size,
    };
  },
};

interface BackendAuditLog {
  staffNo: string;
  staffName: string;
  action: string;
  target: string;
  detail: string;
  at: number;
}
