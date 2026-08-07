// 覆盖范围：员工与权限（P-1.1）。
import { client } from "../http-client";
import type { IamApi } from "../contracts/iam";

export const iamHttp: IamApi = {
  listStaffs: (q) => client.get("/ops/staffs", q),
  setStaffEnabled: (no, enabled) => client.post(`/ops/staffs/${no}/enabled`, { enabled }),
  setStaffRole: (no, role) => client.post(`/ops/staffs/${no}/role`, { role }),
  setStaffScope: (no, scope) => client.post(`/ops/staffs/${no}/scope`, scope),
  listRoles: () => client.get("/ops/roles"),
  setRolePerms: (role, perms) => client.post(`/ops/roles/${role}/perms`, { perms }),
  listAuditLogs: (q) => client.get("/ops/audit-logs", q),
};
