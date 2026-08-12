// 覆盖范围：员工账号、角色与 RBAC、数据域授权、操作审计（P-1.1）。
import type { AuditLog, MenuFunction, Page, RoleDef, Staff } from "@/lib/types";
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

  // ── 角色与功能点 ───────────────────────────────────────────────────────
  //
  // **2026-08-12 换源**：原来这一组调的是 `/ops/roles`，后端从来没有过那个路径 ——
  // 页面有完整的权限树和保存按钮，点下去 404。缺口在守卫里诚实登记着，
  // 但对着屏幕的人不知道。
  //
  // 现在接的是 `/ops/perm/**`，且授权单位从**权限码**换成**功能点**：
  // 勾什么 = 库里存什么 = 那个人登录后菜单长什么样，中间没有翻译层。

  listRoles(): Promise<RoleDef[]>;
  /** 功能与功能点全集 —— 权限树的数据源。与 `menu()` 的差别：这个不按人切片。 */
  listPermFunctions(): Promise<MenuFunction[]>;
  /** 某个角色已勾的功能点码。 */
  getRolePoints(roleCode: string): Promise<string[]>;
  /**
   * 改角色的功能点。
   *
   * ⚠️ **内置角色会被拒（10440）**：它们是 `Perms.java` 的镜像，
   * 改了会与回落表分叉，而什么时候回落不由前端决定。UI 上禁用而不是隐藏。
   *
   * 后端会踢掉持有者的会话 —— perms 是登录那一刻算好的快照。
   */
  setRolePoints(roleCode: string, pointCodes: string[]): Promise<RoleDef>;
  createRole(roleCode: string, name: string): Promise<RoleDef>;
  /**
   * 删角色。**还有人在用会被拒（10441）** —— 用 `staffCount` 提前拦在点击之前。
   *
   * 叫 `remove*` 而不是 `delete*`：命名约定禁止 `delete*`，因为这个仓库里
   * 「删」几乎都是软删除（archive/unarchive）。**角色这个是真删**，
   * 用 archive 反而是撒谎 —— 所以取了第三个词。
   */
  removeRole(roleCode: string): Promise<void>;

  /** 审计日志（P-1.1.4）。**只读**：没有 delete/update，合规要求不可篡改。 */
  listAuditLogs(q?: AuditQ): Promise<Page<AuditLog>>;
}
