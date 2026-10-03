// 风控文案（矩阵 P-16.2）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {

  subjectUser: "用户",
  subjectMerchant: "商家",
  subjectDevice: "设备",

  toastConfirmed: "已确认风险",
  toastDismissed: "已排除",
  toastBlacklisted: "已加入黑名单",
  toastAppealAccepted: "申诉成立，已解除拉黑",
  toastAppealRejected: "已驳回申诉",
  toastRuleSaved: "规则已保存",

  colEventNo: "事件号",
  colType: "类型",
  colSubject: "主体",
  /** `{kind}` 主体类型，`{id}` 主体标识 */
  subjectText: "{kind}：{id}",
  colSignals: "命中信号",
  fieldSignals: "命中信号",
  colFoundAt: "发现时间",
  colStatus: "状态",
  colActions: "操作",
  actionHandle: "处置",
  actionView: "查看",

  colBlackNo: "记录号",
  colReason: "原因",
  colUntil: "到期",
  colAppeal: "申诉",
  colActive: "生效中",
  yes: "是",
  no: "否",
  actionDecideAppeal: "裁决申诉",

  readOnlyWhat: "风险处置与黑名单",
  readOnlyNote: "不能确认事件、拉黑或裁决申诉",
  kpiOpen: "待处置事件",
  kpiOpenSub: "有人在等结论",
  kpiOpenNone: "无积压",
  kpiPageCount: "本页事件",
  kpiActiveBlack: "生效中黑名单",
  eventsNotice:
    "命中信号只说明「值得看一眼」，不是判定 —— 所以这里不给风险分。分值口径要等有真实样本后由风控定；现在编一个看起来很准的分数，只会让人照着它做决定。",
  searchEvents: "搜索事件号 / 主体 / 信号",
  filterType: "按类型筛选",
  filterTypeAll: "全部类型",
  filterStatus: "按状态筛选",
  filterStatusAll: "全部状态",
  emptyEvents: "没有符合条件的风险事件。这是好事 —— 不是数据缺失。",

  blacklistNotice:
    "拉黑必须带原因与到期时间：无期限拉黑没有申诉出口，那是产品事故不是风控严格。接受申诉会解除拉黑，但记录保留 —— 留痕，不是删除。",
  searchBlacklist: "搜索记录号 / 主体 / 原因",
  addLabel: "加入黑名单",
  filterSubject: "按主体类型筛选",
  filterSubjectAll: "全部主体",
  filterActive: "生效筛选",
  filterActiveOnly: "仅看生效中",
  filterActiveAll: "全部记录",
  emptyBlacklist: "黑名单是空的。这是好事，不是数据缺失 —— 拉黑必须带原因与到期时间。",

  rulesTitle: "拦截规则",
  rulesReadOnlyWhat: "拦截规则配置",
  rulesNotice:
    "阈值必须大于 0（0 等于全量拦截）。自动拦截关掉时只记事件不动人 —— 新规则上线建议先只记录，观察一段时间再开自动。",
  /** `{time}` 是时间 */
  ruleUpdatedAt: "上次修改 {time}",
  fieldThreshold: "触发阈值",
  /** `{type}` 是规则类型 */
  ariaAutoBlock: "{type} 自动拦截",
  autoBlock: "自动拦截",
  save: "保存",

  btnDismiss: "排除",
  btnConfirmRisk: "确认风险",
  fieldEvidence: "证据",
  linkTrace: "查看归因链路",
  none: "无",
  fieldVerdict: "处置结论",
  verdictPlaceholder: "确认与排除都要写依据：下次同一主体再命中时，得知道上次为什么这么判",

  addTitle: "加入黑名单",
  btnConfirmBlack: "确认拉黑",
  fieldSubjectType: "主体类型",
  fieldSubject: "主体标识",
  subjectPlaceholder: "用户昵称 / 商家名 / 设备号",
  fieldBlackReason: "拉黑原因",
  blackReasonPlaceholder: "被拉黑者申诉时会看到自己因为什么被拉黑",
  fieldUntil: "到期时间（ISO）",
  untilHint: "不允许无期限拉黑 —— 没有申诉出口的封禁是产品事故。",

  /** `{subject}` 是主体标识 */
  appealTitle: "{subject} 的解禁申诉",
  btnRejectAppeal: "驳回申诉",
  btnAcceptAppeal: "申诉成立（解除拉黑）",
  fieldAppealReason: "申诉理由",
  fieldAppealVerdict: "裁决说明",
  appealVerdictPlaceholder: "成立与驳回都要写：被拉黑者会看到这段话",
};

const en: typeof zh = {

  subjectUser: "Customer",
  subjectMerchant: "Merchant",
  subjectDevice: "Device",

  toastConfirmed: "Risk confirmed",
  toastDismissed: "Dismissed",
  toastBlacklisted: "Added to the blacklist",
  toastAppealAccepted: "Appeal upheld — the block is lifted",
  toastAppealRejected: "Appeal rejected",
  toastRuleSaved: "Rule saved",

  colEventNo: "Event no.",
  colType: "Type",
  colSubject: "Subject",
  subjectText: "{kind}: {id}",
  colSignals: "Signals hit",
  fieldSignals: "Signals hit",
  colFoundAt: "Detected at",
  colStatus: "Status",
  colActions: "Actions",
  actionHandle: "Handle",
  actionView: "View",

  colBlackNo: "Record no.",
  colReason: "Reason",
  colUntil: "Expires",
  colAppeal: "Appeal",
  colActive: "In force",
  yes: "Yes",
  no: "No",
  actionDecideAppeal: "Decide appeal",

  readOnlyWhat: "risk handling & blacklist",
  readOnlyNote: "cannot confirm events, block subjects or decide appeals",
  kpiOpen: "Events to handle",
  kpiOpenSub: "Someone is waiting on a decision",
  kpiOpenNone: "Nothing queued",
  kpiPageCount: "Events on this page",
  kpiActiveBlack: "Active blocks",
  eventsNotice:
    "A signal hit only means “worth a look”, not a verdict — which is why there is no risk score here. How to score has to be defined by the risk team once real samples exist; inventing a convincing-looking number now would just get people deciding by it.",
  searchEvents: "Search event no. / subject / signal",
  filterType: "Filter by type",
  filterTypeAll: "All types",
  filterStatus: "Filter by status",
  filterStatusAll: "All statuses",
  emptyEvents: "No risk events match these filters. That is good news — not missing data.",

  blacklistNotice:
    "A block must carry a reason and an expiry: an open-ended block leaves no route to appeal, which is a product failure rather than strict risk control. Upholding an appeal lifts the block but keeps the record — it is a trail, not a delete.",
  searchBlacklist: "Search record no. / subject / reason",
  addLabel: "Add to blacklist",
  filterSubject: "Filter by subject type",
  filterSubjectAll: "All subject types",
  filterActive: "Force filter",
  filterActiveOnly: "In force only",
  filterActiveAll: "All records",
  emptyBlacklist: "The blacklist is empty. That is good news, not missing data — a block always needs a reason and an expiry.",

  rulesTitle: "Interception rules",
  rulesReadOnlyWhat: "interception rule configuration",
  rulesNotice:
    "Thresholds must be above 0 (0 means intercept everything). With auto-block off, events are recorded but nobody is acted on — for a new rule, record only at first and watch it for a while before switching auto-block on.",
  ruleUpdatedAt: "Last changed {time}",
  fieldThreshold: "Trigger threshold",
  ariaAutoBlock: "Auto-block for {type}",
  autoBlock: "Auto-block",
  save: "Save",

  btnDismiss: "Dismiss",
  btnConfirmRisk: "Confirm risk",
  fieldEvidence: "Evidence",
  linkTrace: "View attribution trace",
  none: "None",
  fieldVerdict: "Decision",
  verdictPlaceholder: "Write your reasoning either way: next time the same subject is flagged, someone needs to know why you decided this",

  addTitle: "Add to blacklist",
  btnConfirmBlack: "Confirm block",
  fieldSubjectType: "Subject type",
  fieldSubject: "Subject identifier",
  subjectPlaceholder: "Customer nickname / merchant name / device ID",
  fieldBlackReason: "Reason for blocking",
  blackReasonPlaceholder: "The blocked party sees this when they appeal",
  fieldUntil: "Expires at (ISO)",
  untilHint: "Open-ended blocks are not allowed — a ban with no route to appeal is a product failure.",

  appealTitle: "Unblock appeal from {subject}",
  btnRejectAppeal: "Reject appeal",
  btnAcceptAppeal: "Uphold appeal (lift the block)",
  fieldAppealReason: "Grounds for appeal",
  fieldAppealVerdict: "Decision note",
  appealVerdictPlaceholder: "Write one either way — the blocked party sees this text",
};

export const RISK_COPY: PageCopy<typeof zh> = { zh, en };
