// 覆盖范围：员工与权限（P-1.1）。
import { client } from "../http-client";
import type { IamApi } from "../contracts/iam";
import type { Staff } from "@/lib/types";
import { toOpsRole } from "./dashboard";

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
}

interface StaffPage {
  records: BackendStaff[];
  total: number;
  page: number;
  size: number;
}

/** 一个员工在 ops-web 里只有一个角色；后端是数组，取第一个并翻译角色码 */
function toStaff(s: BackendStaff): Staff {
  return {
    staffNo: s.staffNo,
    username: s.username,
    name: s.realName ?? "",
    role: toOpsRole(s.roles?.[0]),
    merchantNo: s.merchantNo || undefined,
    communityNo: s.communityNo || undefined,
    pickupNo: s.pickupNo || undefined,
    enabled: s.status !== "DISABLED",
    lastLoginAt: s.lastLoginAt ? new Date(s.lastLoginAt).toISOString() : undefined,
    createdAt: "",
  };
}

export const iamHttp: IamApi = {
  listStaffs: async (q) => {
    const p = await client.get<StaffPage>("/ops/staffs", q);
    return { ...p, records: (p?.records ?? []).map(toStaff) };
  },
  setStaffEnabled: async (no, enabled) =>
    toStaff(await client.post<BackendStaff>(`/ops/staffs/${no}/enabled`, { enabled })),
  setStaffRole: async (no, role) =>
    toStaff(await client.post<BackendStaff>(`/ops/staffs/${no}/role`, { role })),
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
  removeRole: (roleCode) => client.post(`/ops/perm/roles/${roleCode}/delete`, {}),
  listAuditLogs: (q) => client.get("/ops/audit-logs", q),
};
