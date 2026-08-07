// 员工与权限域（矩阵 P-1.1 + §2.3）。
//
// ⚠️ **当前角色权限的真源是编译期常量**（lib/permissions.ts 的 ROLE_PERMS），`can()` 读的是它。
// 本域编辑的是 mock 层的角色定义，**接后端前不会改变当前登录用户的实际权限**。
// 这条边界是刻意的：`can()` 若改成运行时可变，权限判定就依赖一份可被页面改写的状态，
// mock 阶段一次误操作就能把自己锁在外面。页面上有明写，测试也锁住了这个事实。
import type { Role } from "../auth";

export interface Staff {
  /** 员工单号 */
  staffNo: string;
  /** 登录名 */
  username: string;
  /** 姓名 */
  name: string;
  /** 角色。决定权限码集合，见 `RoleDef` */
  role: Role;
  /**
   * 数据域（P-1.1.3）。只对**受限角色**有意义：
   * 社区运营 → communityNo、商家运营 → merchantNo。
   * 给全量角色（超管等）配数据域是配置错误 —— 会让人以为它被限制了，实际没有。
   */
  merchantNo?: string;
  /** 社区运营的社区数据域 */
  communityNo?: string;
  /** 自提点数据域 */
  pickupNo?: string;
  /** 是否启用。停用后立即无法登录，历史操作留痕保留 */
  enabled: boolean;
  /** 最近登录时间。从未登录为空 */
  lastLoginAt?: string;
  /** 建档时间 */
  createdAt: string;
}

export interface RoleDef {
  /** 角色码 */
  role: Role;
  /** 角色展示名 */
  label: string;
  /** 内置角色（超管）：定义就是"全部"，不可编辑 —— 可编辑意味着能把自己降权 */
  builtin: boolean;
  /** 权限码集合；'*' 表示全部 */
  perms: string[];
  /** 持有该角色的账号数 */
  staffCount: number;
}

/** 审计日志（P-1.1.4）。只读不可删（合规）。 */
export interface AuditLog {
  /** 日志单号 */
  logNo: string;
  /** 操作时间 */
  at: string;
  /** 操作人（STAFF 账号） */
  operator: string;
  /** 动作描述，如「授予角色权限」「停用员工」 */
  action: string;
  /** 操作对象，如员工号 / 角色名 */
  target: string;
  /** 详细内容，含变更前后值 */
  detail: string;
  /** 是否涉及高危权限（矩阵 §2.3 的那批码） */
  critical: boolean;
}

/** 需要数据域的角色。其余角色配了 scope 属于配置错误。 */
export const SCOPED_ROLES: Role[] = ["COMMUNITY_OPS", "MERCHANT_BD"];
