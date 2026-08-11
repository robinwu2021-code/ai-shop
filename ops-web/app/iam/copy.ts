// 员工与权限文案（矩阵 P-1.1）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  tabStaffs: "员工账号",
  tabRoles: "角色与权限",
  tabAudit: "操作审计",

  enabledOn: "启用中",
  enabledOff: "已停用",

  toastEnabled: "已启用",
  toastDisabled: "已停用",
  toastRoleChanged: "已调整角色",
  toastScopeChanged: "已调整数据域",
  toastPermsSaved: "已保存角色权限",
  critical: "高危",

  colStaffNo: "工号",
  colName: "姓名",
  colUsername: "登录名",
  colRole: "角色",
  /** `{name}` 是员工姓名 */
  ariaRoleOf: "{name} 的角色",
  /** `{name}` 是员工姓名 */
  confirmPromoteTitle: "将 {name} 升为超级管理员",
  confirmPromoteDesc: "超管拥有全部权限，包括授权、封禁、打款与环境切换。请输入工号确认。",
  confirmPromoteOk: "确认升权",
  colScope: "数据域",
  scopeAll: "全量",
  /** `{no}` 是编号 */
  scopeMerchant: "商家 {no}",
  scopeCommunity: "社区 {no}",
  scopePickup: "自提点 {no}",
  scopeUnbounded: "未限定（等同全量）",
  colLastLogin: "最近登录",
  colEnabled: "启用",
  ariaEnableSwitch: "{name} 启用开关",
  /** `{name}` 是员工姓名 */
  confirmDisableTitle: "停用超级管理员 {name}",
  confirmDisableDesc: "停用最后一个超管会让系统失去管理入口（服务端会拒绝）。请输入工号确认。",
  confirmDisableOk: "确认停用",
  colActions: "操作",
  actionScope: "配数据域",

  colRoleLabel: "角色",
  colRoleCode: "编码",
  colStaffCount: "账号数",
  colPermCount: "权限数",
  permAll: "全部",
  colCriticalPerms: "高危权限",
  /** `{n}` 是高危权限项数 */
  criticalCount: "{n} 项",
  roleBuiltIn: "内置不可改",
  actionPerms: "配权限",

  colTime: "时间",
  colOperator: "操作人",
  colAction: "动作",
  colTarget: "对象",
  colDetail: "详情",
  colCritical: "高危",
  yes: "是",
  no: "否",
  none: "无",

  readOnlyWhat: "账号与权限管理",
  readOnlyNote: "不能改角色、数据域或权限",
  notice:
    "当前角色权限的真源是代码里的常量（lib/permissions.ts），can() 读的是它。这里的编辑作用于 mock 数据，「接后端前不会改变当前登录用户的实际权限」—— 之所以不做成运行时可变：那样一次误操作就能把自己锁在系统外面。",
  searchStaff: "搜索工号 / 姓名 / 登录名",
  searchAudit: "搜索操作人 / 动作 / 对象",
  filterRole: "按角色筛选",
  filterRoleAll: "全部角色",
  filterStatus: "按状态筛选",
  filterStatusAll: "全部状态",
  filterCritical: "按高危筛选",
  filterCriticalOnly: "仅看高危操作",
  filterCriticalAll: "全部操作",
  emptyStaff: "没有符合条件的员工。清空筛选，或换个角色看看。",
  emptyRoles: "没有角色定义。角色是权限的载体，没有角色就没法给员工授权。",

  /** `{role}` 是角色名 */
  permCardTitle: "{role} 的权限",
  permCardEmpty: "权限配置",
  permCardHint: "在左侧选一个角色的「配权限」，这里出权限树。",
  emptyPerms: "没有可配置的权限码。权限码来自各业务域的声明，缺了说明该域还没接入 RBAC。",
  /** `{role}` 是角色名 */
  confirmGrantTitle: "授予 {role} 高危权限",
  /** `{n}` 项数，`{list}` 权限码列表 */
  confirmGrantDesc: "本次包含 {n} 项高危权限：{list}。请输入角色编码确认。",
  confirmGrantOk: "确认授权",
  save: "保存",
  /** `{n}` 是已选数量 */
  selectedN: "已选 {n} 项",

  auditNotice:
    "审计日志「只读不可删」（合规）。当前覆盖本域的写操作；其它域的操作留痕等接后端后由后端统一记录 —— 前端埋点会漏，且能被绕过，不可信。",
  auditReadOnlyWhat: "操作审计日志",
  emptyAudit: "没有符合条件的审计记录。换个操作人或时间范围看看 —— 高危操作一定会留痕。",

  /** `{name}` 是员工姓名 */
  scopeDrawerTitle: "{name} 的数据域",
  scopeNotice:
    "⚠️ 配置会保存，但**裁剪尚未生效**：后端各域的查询目前还没有按数据域过滤，配了也仍然能看到全量。按它裁剪是单独一批。\n\n数据域只对受限角色生效（社区运营、商家运营）。留空 = 不限定，等同全量 —— 这一点在列表里会用橙色标出来，避免“以为限制了其实没限制”。",
  phCommunity: "如 C001",
  phMerchant: "如 M903",
  phPickup: "如 P001",
  fieldCommunityNo: "社区编号",
  fieldMerchantNo: "商家编号",
  fieldPickupNo: "自提点编号",
  fieldHowItWorks: "生效方式",
  scopeHowHint:
    "列表查询会自动带上这些归属键，mock 与真实后端行为一致。越权拦截以后端为准（矩阵 §2.3）—— 前端只做展示裁剪。",
};

const en: typeof zh = {
  tabStaffs: "Staff accounts",
  tabRoles: "Roles & permissions",
  tabAudit: "Audit log",

  enabledOn: "Active",
  enabledOff: "Disabled",

  toastEnabled: "Enabled",
  toastDisabled: "Disabled",
  toastRoleChanged: "Role updated",
  toastScopeChanged: "Data scope updated",
  toastPermsSaved: "Role permissions saved",
  critical: "High risk",

  colStaffNo: "Staff no.",
  colName: "Name",
  colUsername: "Username",
  colRole: "Role",
  ariaRoleOf: "Role of {name}",
  confirmPromoteTitle: "Promote {name} to super admin",
  confirmPromoteDesc:
    "A super admin holds every permission, including granting access, suspending merchants, releasing payouts and switching environments. Type the staff number to confirm.",
  confirmPromoteOk: "Promote",
  colScope: "Data scope",
  scopeAll: "Unrestricted",
  scopeMerchant: "Merchant {no}",
  scopeCommunity: "Community {no}",
  scopePickup: "Pickup point {no}",
  scopeUnbounded: "Unbounded (same as unrestricted)",
  colLastLogin: "Last sign-in",
  colEnabled: "Enabled",
  ariaEnableSwitch: "Enable {name}",
  confirmDisableTitle: "Disable super admin {name}",
  confirmDisableDesc:
    "Disabling the last super admin would leave the system with no way in (the server rejects it). Type the staff number to confirm.",
  confirmDisableOk: "Disable",
  colActions: "Actions",
  actionScope: "Set scope",

  colRoleLabel: "Role",
  colRoleCode: "Code",
  colStaffCount: "Accounts",
  colPermCount: "Permissions",
  permAll: "All",
  colCriticalPerms: "High-risk permissions",
  criticalCount: "{n}",
  roleBuiltIn: "Built-in, read-only",
  actionPerms: "Set permissions",

  colTime: "Time",
  colOperator: "Operator",
  colAction: "Action",
  colTarget: "Target",
  colDetail: "Details",
  colCritical: "High risk",
  yes: "Yes",
  no: "No",
  none: "None",

  readOnlyWhat: "account & permission management",
  readOnlyNote: "cannot change roles, data scopes or permissions",
  notice:
    "The source of truth for role permissions is a constant in the code (lib/permissions.ts) — that is what can() reads. Edits here apply to mock data and will not change the signed-in user's real permissions before the backend is wired up. It is deliberately not runtime-mutable: one slip would lock you out of your own system.",
  searchStaff: "Search staff no. / name / username",
  searchAudit: "Search operator / action / target",
  filterRole: "Filter by role",
  filterRoleAll: "All roles",
  filterStatus: "Filter by status",
  filterStatusAll: "All statuses",
  filterCritical: "Filter by risk",
  filterCriticalOnly: "High-risk actions only",
  filterCriticalAll: "All actions",
  emptyStaff: "No staff match these filters. Clear them, or try another role.",
  emptyRoles: "No roles defined. Roles carry permissions — without one there is nothing to grant staff.",

  permCardTitle: "Permissions for {role}",
  permCardEmpty: "Permissions",
  permCardHint: "Pick “Set permissions” on a role at the left and its permission tree appears here.",
  emptyPerms: "No permission codes to configure. Codes are declared by each business domain; missing ones mean that domain has not adopted RBAC yet.",
  confirmGrantTitle: "Grant high-risk permissions to {role}",
  confirmGrantDesc: "This includes {n} high-risk permission(s): {list}. Type the role code to confirm.",
  confirmGrantOk: "Grant",
  save: "Save",
  selectedN: "{n} selected",

  auditNotice:
    "The audit log is append-only by design (compliance). It currently covers writes in this domain; other domains will be recorded by the backend once connected — front-end instrumentation misses events and can be bypassed, so it is not trustworthy.",
  auditReadOnlyWhat: "audit log",
  emptyAudit: "No audit records match. Try another operator or time range — high-risk actions are always recorded.",

  scopeDrawerTitle: "Data scope for {name}",
  scopeNotice:
    "⚠️ The setting is saved, but **filtering is not live yet**: backend queries do not honour data scope today, so a scoped account still sees everything. Enforcement ships separately.\n\nData scope only applies to restricted roles (community ops, merchant ops). Leaving it blank means unbounded, i.e. the same as unrestricted — the list marks that in amber so nobody assumes a limit that is not there.",
  phCommunity: "e.g. C001",
  phMerchant: "e.g. M903",
  phPickup: "e.g. P001",
  fieldCommunityNo: "Community no.",
  fieldMerchantNo: "Merchant no.",
  fieldPickupNo: "Pickup point no.",
  fieldHowItWorks: "How it works",
  scopeHowHint:
    "List queries carry these ownership keys automatically, and the mock behaves like the real backend. Authorisation is enforced server-side (matrix §2.3) — the front end only trims what is shown.",
};

export const IAM_COPY: PageCopy<typeof zh> = { zh, en };
