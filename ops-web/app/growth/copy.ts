// 增长与归因文案（矩阵 P-9.1 / P-9.2）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {

  srcStoreCode: "店铺码",
  srcInviter: "邀请人",
  srcChannel: "渠道",

  conflictKeepFirst: "保留首次归因",
  conflictKeepFirstHint: "对先把人拉来的商家公平；但用户后来常去的店拿不到归因",
  conflictOverwrite: "后者覆盖",
  conflictOverwriteHint: "对当前正在服务用户的商家公平；但会激励商家反复诱导扫码",
  conflictAskUser: "让用户选",
  conflictAskUserHint: "最准；但多一步打断，进店转化会掉",

  factorDevice: "设备",
  factorDeviceHint: "挡批量注册；但一家人共用一台设备会被误伤",
  factorPhone: "手机号",
  factorPhoneHint: "挡小号；但接码平台能绕",

  toastRuleSaved: "归因规则已保存",
  toastFissionSaved: "已更新活动状态",

  colTraceNo: "链路号",
  colUser: "用户",
  colSource: "归因来源",
  colSourceRef: "载体",
  colAttributedAt: "归因时间",
  colFirstOrder: "首单",
  noOrder: "未下单",
  colConflict: "冲突",
  /** `{no}` 是冲突对象的链路号 */
  conflictWith: "与 {no}",
  colRiskFlags: "风控信号",
  none: "无",

  colFissionNo: "活动号",
  colName: "名称",
  colReward: "奖励",
  /** `{no}` 是券号 */
  rewardCoupon: "券 {no}",
  colInviterGets: "邀请人得",
  colInviteeGets: "被邀请人得",
  /** `{n}` 是张数 */
  coupons: "{n} 张",
  colInvited: "已邀请",
  colConverted: "已转化",
  colEnabled: "启用",
  /** `{name}` 是活动名 */
  ariaEnable: "{name} 启用开关",

  ruleTitle: "归因规则",
  ruleReadOnlyWhat: "归因规则配置",
  ruleNotice:
    "店铺码归因规则（P-9.1.5）在矩阵里标着「B1 未拍板」：用户已归属 A 店又扫了 B 店的码算谁的。三种处置各有代价，下面都写出来了 —— 定了之后改的是默认值，不是模型。",
  priorityTitle: "归因优先级（高 → 低）",
  moveUp: "上移",
  /** `{n}` 是来源总数 */
  priorityHint: "必须覆盖全部 {n} 个来源且不重复 —— 半个优先级表在冲突时会随机裁决。",
  fieldWindow: "归因窗口期（天）",
  /** `{min}` / `{max}` 是天数边界 */
  windowHint: "{min}–{max} 天。0 天等于关掉归因；超过 90 天的归因没有业务意义。",
  conflictTitle: "店铺码冲突处置（B1）",
  factorTitle: "新客判定因子（P-9.2.3）",
  factorHint: "至少选一个 —— 一个都不选等于所有人都是新客，新人券会被无限领。",

  tracesReadOnlyWhat: "归因链路查询",
  tracesNotice:
    "风控页的「异常裂变」事件就是从这条链路上看出来的：同设备批量注册后互相邀请，在这里表现为一串归因时间相邻、载体是同一个邀请人的记录。",
  searchPlaceholder: "搜索链路号 / 用户 / 载体 / 订单号",
  filterSource: "按来源筛选",
  filterSourceAll: "全部来源",
  filterConflict: "冲突筛选",
  filterConflictOnly: "仅看归因冲突",
  filterConflictAll: "不限冲突",
  filterRisk: "风控筛选",
  filterRiskOnly: "仅看命中风控信号",
  filterRiskAll: "不限风控",
  emptyTraces: "没有符合条件的归因链路。用户扫码进店或被邀请后才会产生记录。",

  fissionNotice:
    "邀请有礼的奖励「只能是券」：去团长化后（ADR-004）不存在现金激励 —— 一旦发现金，职业薅羊毛立刻回来，且归因作弊有了直接变现路径。",
  emptyFission: "还没有裂变活动。奖励券模板在「营销活动」里先建好，这里再引用。",
};

const en: typeof zh = {

  srcStoreCode: "Store code",
  srcInviter: "Inviter",
  srcChannel: "Channel",

  conflictKeepFirst: "Keep the first attribution",
  conflictKeepFirstHint: "Fair to whoever brought the customer in; but the shop they actually frequent gets nothing",
  conflictOverwrite: "Latest wins",
  conflictOverwriteHint: "Fair to whoever serves the customer now; but it rewards merchants for nagging people to rescan",
  conflictAskUser: "Ask the customer",
  conflictAskUserHint: "Most accurate; but it adds an interruption and store-visit conversion drops",

  factorDevice: "Device",
  factorDeviceHint: "Blocks bulk sign-ups; but a family sharing one device gets caught too",
  factorPhone: "Phone number",
  factorPhoneHint: "Blocks throwaway accounts; but SMS-relay services get around it",

  toastRuleSaved: "Attribution rules saved",
  toastFissionSaved: "Campaign status updated",

  colTraceNo: "Trace no.",
  colUser: "Customer",
  colSource: "Attributed to",
  colSourceRef: "Carrier",
  colAttributedAt: "Attributed at",
  colFirstOrder: "First order",
  noOrder: "No order yet",
  colConflict: "Conflict",
  conflictWith: "with {no}",
  colRiskFlags: "Risk signals",
  none: "None",

  colFissionNo: "Campaign no.",
  colName: "Name",
  colReward: "Reward",
  rewardCoupon: "Coupon {no}",
  colInviterGets: "Inviter gets",
  colInviteeGets: "Invitee gets",
  coupons: "{n}",
  colInvited: "Invited",
  colConverted: "Converted",
  colEnabled: "Enabled",
  ariaEnable: "Enable {name}",

  ruleTitle: "Attribution rules",
  ruleReadOnlyWhat: "attribution rule configuration",
  ruleNotice:
    "The store-code attribution rule (P-9.1.5) is still marked “B1 undecided” in the matrix: who gets credit when a customer already attributed to shop A scans shop B's code. All three options have a cost, spelled out below — settling it changes the default, not the model.",
  priorityTitle: "Attribution priority (high → low)",
  moveUp: "Move up",
  priorityHint: "Must cover all {n} sources exactly once — a half-filled priority table settles conflicts at random.",
  fieldWindow: "Attribution window (days)",
  windowHint: "{min}–{max} days. Zero days switches attribution off; anything past 90 days carries no business meaning.",
  conflictTitle: "Store-code conflict handling (B1)",
  factorTitle: "New-customer criteria (P-9.2.3)",
  factorHint: "Pick at least one — with none selected everybody counts as new and new-customer coupons can be farmed endlessly.",

  tracesReadOnlyWhat: "attribution trace lookup",
  tracesNotice:
    "The “abnormal referral” events on the risk page are spotted from these traces: bulk sign-ups on one device inviting each other show up here as a run of records attributed within moments of each other to the same inviter.",
  searchPlaceholder: "Search trace no. / customer / carrier / order no.",
  filterSource: "Filter by source",
  filterSourceAll: "All sources",
  filterConflict: "Conflict filter",
  filterConflictOnly: "Conflicts only",
  filterConflictAll: "Any conflict state",
  filterRisk: "Risk filter",
  filterRiskOnly: "Risk signals only",
  filterRiskAll: "Any risk state",
  emptyTraces: "No attribution traces match. Records appear once a customer scans into a store or is invited.",

  fissionNotice:
    "Referral rewards can only be coupons: after moving away from the group-leader model (ADR-004) there is no cash incentive — cash brings professional farmers straight back and gives attribution fraud a direct payout.",
  emptyFission: "No referral campaigns yet. Create the reward coupon template under Marketing first, then reference it here.",
};

export const GROWTH_COPY: PageCopy<typeof zh> = { zh, en };
