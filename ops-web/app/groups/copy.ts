// 团购与求团文案（矩阵 P-8.1 / P-8.2）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  tabCampaigns: "商家团",
  tabDemands: "求团需求",
  tabQuotes: "报价与信用",

  toastAuditPassed: "已通过，团已开始",
  toastAuditRejected: "已驳回，原因已发给商家",
  toastAssigned: "已指派报价，发起人可在 C 端看到",
  /** `{n}` 是第几次改价 */
  toastPriceChanged: "已改价并公示（第 {n} 次）",
  toastBreachLogged: "已记毁约，将计入商家信用档案",

  colGroupNo: "团编号",
  colMerchant: "商家",
  colSku: "商品",
  colOriginPrice: "原价",
  colGroupPrice: "团购价",
  colProgress: "成团进度",
  colEndAt: "截止",
  colStatus: "状态",
  colActions: "操作",
  actionAudit: "审核",

  colDemandNo: "需求单号",
  colDemand: "需求",
  colInitiator: "发起人",
  colCommunity: "社区",
  colPlusOne: "+1 人数",
  colQuoteCount: "报价数",
  colCreatedAt: "发起时间",
  actionAssign: "指派报价",

  colQuoteNo: "报价单号",
  colUnitPrice: "单价",
  ariaNewPrice: "新单价（元）",
  save: "存",
  cancel: "取消",
  colMinQty: "起订量",
  colPriceChanges: "改价次数",
  /** `{n}` 是改价次数 */
  priceLocked: "{n}（已锁）",
  colValidTo: "有效期至",
  colBreach: "毁约",
  breached: "已毁约",
  notBreached: "否",
  /** `{name}` 是商家名 */
  confirmBreachTitle: "记录毁约：{name}",
  confirmBreachDesc: "毁约会计入商家信用档案，累计达到上限后该商家将无法再对新需求报价。这一步不可撤销。",
  confirmBreachOk: "记毁约",
  actionBreach: "记毁约",

  readOnlyAuditWhat: "团模板审核",
  readOnlyAuditNote: "不能通过或驳回",
  readOnlyMatchWhat: "求团撮合与报价管理",
  readOnlyMatchNote: "不能指派商家、改价或记毁约",
  demandNotice:
    "一期靠运营人肉撮合（P-8.2.2）：看到 +1 人数够的需求，就去找合适的商家指派一条报价。发起人在 C 端对比后选定，中期再做类目自动推送。",
  /** `{max}` 改价上限，`{breach}` 毁约上限 */
  quoteNotice:
    "按 ADR-003：报价不做事前审核。改价允许但每次公示，累计 {max} 次后锁定；毁约累计 {breach} 次的商家禁止对新需求报价（与商家信用档案同源）。",
  searchCampaigns: "搜索团编号 / 商家 / 商品",
  searchDemands: "搜索需求单号 / 标题 / 发起人",
  searchQuotes: "搜索报价单号 / 需求 / 商家",
  filterStatus: "按状态筛选",
  filterStatusAll: "全部状态",
  emptyCampaigns: "没有符合条件的团。商家在 B 端配好起团人数与团购价后会到这里等审核。",
  emptyDemands: "没有符合条件的求团需求。用户在 C 端发起「我想团…」后会出现在这里。",
  emptyQuotes: "还没有报价。到「求团需求」里给合适的商家指派一条。",

  btnReject: "驳回",
  btnPass: "通过",
  fieldMinCount: "起团人数",
  fieldEndAt: "截止时间",
  auditHint: "通过前系统会再校验两条硬规则：起团人数至少 2（1 个人不叫团）、团购价必须低于原价（否则「团购」是假的）。",
  fieldRejectReason: "驳回原因",
  rejectPlaceholder: "驳回时必填，商家会原样看到",

  /** `{title}` 是需求标题 */
  assignTitle: "为「{title}」指派报价",
  /** `{community}` 社区，`{n}` +1 人数 */
  assignDesc: "{community} · +1 {n} 人",
  btnConfirmAssign: "确认指派",
  fieldAssignMerchant: "指派商家",
  assignHint: "同一需求同一商家只能有一条报价；毁约达上限的商家会被服务端拒绝。",
  fieldAssignPrice: "单价（元）",
  fieldAssignQty: "起订量",
};

const en: typeof zh = {
  tabCampaigns: "Merchant group buys",
  tabDemands: "Group requests",
  tabQuotes: "Quotes & credit",

  toastAuditPassed: "Approved — the group buy is live",
  toastAuditRejected: "Rejected — the reason has been sent to the merchant",
  toastAssigned: "Quote assigned — the requester can see it in the C-end app",
  toastPriceChanged: "Price changed and published (change #{n})",
  toastBreachLogged: "Breach logged — it counts toward the merchant's credit record",

  colGroupNo: "Group no.",
  colMerchant: "Merchant",
  colSku: "Product",
  colOriginPrice: "List price",
  colGroupPrice: "Group price",
  colProgress: "Progress",
  colEndAt: "Closes",
  colStatus: "Status",
  colActions: "Actions",
  actionAudit: "Review",

  colDemandNo: "Request no.",
  colDemand: "Request",
  colInitiator: "Requester",
  colCommunity: "Community",
  colPlusOne: "+1 count",
  colQuoteCount: "Quotes",
  colCreatedAt: "Raised at",
  actionAssign: "Assign quote",

  colQuoteNo: "Quote no.",
  colUnitPrice: "Unit price",
  ariaNewPrice: "New unit price (CNY)",
  save: "Save",
  cancel: "Cancel",
  colMinQty: "Min. order",
  colPriceChanges: "Price changes",
  priceLocked: "{n} (locked)",
  colValidTo: "Valid until",
  colBreach: "Breach",
  breached: "Breached",
  notBreached: "No",
  confirmBreachTitle: "Log a breach: {name}",
  confirmBreachDesc:
    "A breach goes on the merchant's credit record; past the limit they can no longer quote on new requests. This cannot be undone.",
  confirmBreachOk: "Log breach",
  actionBreach: "Log breach",

  readOnlyAuditWhat: "group-buy template review",
  readOnlyAuditNote: "cannot approve or reject",
  readOnlyMatchWhat: "request matching & quote management",
  readOnlyMatchNote: "cannot assign merchants, change prices or log breaches",
  demandNotice:
    "Phase 1 matches by hand (P-8.2.2): when a request has enough +1s, find a suitable merchant and assign them a quote. The requester compares and picks in the C-end app; automatic category-based push comes later.",
  quoteNotice:
    "Per ADR-003 quotes are not reviewed up front. Price changes are allowed but each one is published, and the quote locks after {max} of them; a merchant with {breach} breaches is barred from quoting on new requests (same source as the merchant credit record).",
  searchCampaigns: "Search group no. / merchant / product",
  searchDemands: "Search request no. / title / requester",
  searchQuotes: "Search quote no. / request / merchant",
  filterStatus: "Filter by status",
  filterStatusAll: "All statuses",
  emptyCampaigns: "No group buys match these filters. They arrive here for review once a merchant sets a minimum headcount and group price in the B-end app.",
  emptyDemands: "No group requests match these filters. They appear once customers raise “I'd like to group-buy…” in the C-end app.",
  emptyQuotes: "No quotes yet. Go to Group requests and assign one to a suitable merchant.",

  btnReject: "Reject",
  btnPass: "Approve",
  fieldMinCount: "Minimum headcount",
  fieldEndAt: "Closing time",
  auditHint:
    "Two hard rules are re-checked on approval: at least 2 people (one person is not a group), and the group price must be below the list price (otherwise it is not a group buy).",
  fieldRejectReason: "Rejection reason",
  rejectPlaceholder: "Required when rejecting — the merchant sees it verbatim",

  assignTitle: "Assign a quote for “{title}”",
  assignDesc: "{community} · {n} people +1'd",
  btnConfirmAssign: "Confirm assignment",
  fieldAssignMerchant: "Merchant",
  assignHint: "One merchant can hold only one quote per request; merchants at the breach limit are rejected by the server.",
  fieldAssignPrice: "Unit price (CNY)",
  fieldAssignQty: "Minimum order",
};

export const GROUPS_COPY: PageCopy<typeof zh> = { zh, en };
