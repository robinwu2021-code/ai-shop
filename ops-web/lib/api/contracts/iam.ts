// 覆盖范围：员工账号、角色与 RBAC、数据域授权、操作审计（P-1.1）。
import type { AuditLog, MenuFunction, Page, RoleDef, Staff } from "@/lib/types";
import type { Role } from "@/lib/auth";
import type { AuditQ, StaffQ } from "../query";

export interface IamApi {
  listStaffs(q?: StaffQ): Promise<Page<Staff>>;
  /** 停用/启用（软删除语义，不删账号 —— 审计要能追溯到人）。 */
  setStaffEnabled(staffNo: string, enabled: boolean): Promise<Staff>;
  /**
   * 新建员工。**返回的初始密码只出现这一次** —— 关掉抽屉就再也取不到。
   *
   * 密码由后端生成而不是界面传：收明文的问题不是加密与否，
   * 是谁都能在 devtools 里看到刚给同事设的密码，且它会顺着请求体进日志。
   */
  createStaff(username: string, realName: string, roles: string[]): Promise<{ staff: Staff; initialPassword: string }>;
  /**
   * 改角色（**多角色**）。权限取并集。
   *
   * ⚠️ **不能改自己**（10420）—— 否则有 iam:staff:update 的人能给自己加超管。
   * UI 上把自己那行的编辑入口禁掉，别让人点完才知道。
   */
  setStaffRoles(staffNo: string, roles: string[]): Promise<Staff>;
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
  /**
   * 菜单调序：同级内上移/下移。
   *
   * 顺序存在库里（`sys_function_point.sort`），改完所有人的菜单与页面 tab 都跟着变
   * —— 它不是个人偏好，所以挂在 `iam:role:grant` 上。
   * 边界（首项上移/末项下移）是 no-op，不报错。
   */
  movePermFunction(functionCode: string, direction: "UP" | "DOWN"): Promise<void>;
  movePermPoint(pointCode: string, direction: "UP" | "DOWN"): Promise<void>;
  /**
   * 整段重排（拖动用）：传该父级下的**完整顺序**。
   *
   * 与 move 并存而不是取代它 —— ↑/↓ 是键盘可达的那条路，
   * 而原生拖拽在触屏与辅助技术下不可靠。
   * 服务端会校验 codes 与现有集合完全相同，只是顺序不同。
   */
  reorderPermFunctions(codes: string[]): Promise<void>;
  reorderPermPoints(functionCode: string, codes: string[]): Promise<void>;
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
  /** 改角色展示名。**只改名不改码** —— 码是授权的键。预置角色会被拒（10440）。 */
  renameRole(roleCode: string, name: string): Promise<RoleDef>;
  /**
   * 删角色。**还有人在用会被拒（10441）** —— 用 `staffCount` 提前拦在点击之前。
   *
   * 叫 `remove*` 而不是 `delete*` / `archive*`：命名约定禁止 `delete*`，
   * 而 `archive*` 按约定要配一个 `unarchive*`，角色没有「恢复」这条路。
   *
   * ⚠️ 实测更正：它**是逻辑删除**（行还在，`deleted=1`），
   * 我一开始在这里写的「角色这个是真删」是错的。
   */
  removeRole(roleCode: string): Promise<void>;

  /** 审计日志（P-1.1.4）。**只读**：没有 delete/update，合规要求不可篡改。 */
  listAuditLogs(q?: AuditQ): Promise<Page<AuditLog>>;
}
