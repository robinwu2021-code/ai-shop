// 覆盖范围：员工账号、角色与 RBAC、数据域授权、操作审计（P-1.1）。
import type { AuditLog, Page, RoleDef, Staff } from "@/lib/types";
import type { Role } from "@/lib/auth";
import type { AuditQ, StaffQ } from "../query";

export interface IamApi {
  listStaffs(q?: StaffQ): Promise<Page<Staff>>;
  /** 停用/启用（软删除语义，不删账号 —— 审计要能追溯到人）。 */
  setStaffEnabled(staffNo: string, enabled: boolean): Promise<Staff>;
  /** 改角色。 */
  setStaffRole(staffNo: string, role: Role): Promise<Staff>;
  /**
   * 数据域授权（P-1.1.3）。只对受限角色有意义 ——
   * 给全量角色配 scope 会让人以为它被限制了，实际没有，所以直接拒绝。
   */
  setStaffScope(staffNo: string, scope: { merchantNo?: string; communityNo?: string; pickupNo?: string }): Promise<Staff>;

  listRoles(): Promise<RoleDef[]>;
  /** 改角色权限。内置角色（超管）不可编辑。⚠️ mock 阶段不改变 can() 的判定，见 lib/types/iam.ts。 */
  setRolePerms(role: Role, perms: string[]): Promise<RoleDef>;

  /** 审计日志（P-1.1.4）。**只读**：没有 delete/update，合规要求不可篡改。 */
  listAuditLogs(q?: AuditQ): Promise<Page<AuditLog>>;
}
