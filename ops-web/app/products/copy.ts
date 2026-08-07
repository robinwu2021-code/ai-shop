// 商品与类目文案（矩阵 P-3.1 / P-3.2 / P-3.3）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  tabCategories: "类目树",
  tabSkus: "商品池",
  tabStock: "库存与预售",

  marketCN: "中国",
  marketSG: "新加坡",

  toastApproved: "已通过，商品已上架",
  toastRejected: "已驳回，原因已发给商家",
  toastForcedOff: "已强制下架",
  toastPresaleSaved: "预售配置已保存",
  toastCatArchived: "类目已归档",

  /** `{n}` 是在售数 */
  catOnSale: "（在售 {n}）",
  detail: "详情",

  colSkuNo: "商品编码",
  colTitle: "商品",
  colMerchant: "商家",
  colCategory: "类目",
  colPricing: "多市场定价",
  /** `{m}` 是市场代码 */
  priceMissing: "{m} 缺价",
  colI18n: "译文",
  /** `{langs}` 是缺失语言 */
  i18nMissing: "缺 {langs}",
  i18nComplete: "齐全",
  colStock: "库存",
  colStatus: "状态",
  colActions: "操作",
  actionAudit: "审核",
  actionView: "查看",

  colPresaleQuota: "预售额度",
  colSold: "已售",
  colCutoffAt: "截单时间",
  colArriveAt: "到货时间",
  actionEditPresale: "改配置",

  readOnlyWhat: "商品审核与强制下架",
  readOnlyNote: "不能通过、驳回或下架",

  catTreeTitle: "三级类目树",
  catTreeNotice: "最多三级。归档前会检查：还挂着子类目或在售商品的类目不能归档 —— 否则 C 端类目树会出现走不通的分支。",
  loading: "加载中…",
  emptyTree: "还没有类目。类目树是商品的骨架，缺它商家无法提审商品。",
  catDetailTitle: "类目详情",
  catDetailHint: "点左侧任一类目的「详情」，查看它的模板、资质与多语言名称。",
  fieldCatNo: "类目编号",
  fieldLevel: "层级",
  /** `{n}` 是级数 */
  levelN: "{n} 级",
  fieldTemplate: "属性模板",
  fieldQualification: "资质要求",
  /** `{code}` 是校验编码 */
  requiredCode: "校验编码：{code}",
  noQualification: "无门槛",
  fieldI18nName: "多语言名称",
  i18nFallback: "缺失（回落到中文）",
  fieldSkuCount: "在售商品",
  /** `{name}` 是类目名 */
  confirmArchiveTitle: "归档类目 {name}",
  confirmArchiveDesc: "归档后该类目不再出现在 C 端类目树；若它还有子类目或在售商品，服务端会拒绝。",
  confirmArchiveOk: "归档",
  btnArchiveCat: "归档该类目",

  searchSku: "搜索商品编码 / 标题 / 商家",
  filterStatus: "按状态筛选",
  filterStatusAll: "全部状态",
  emptySku: "没有符合条件的商品。商家在 B 端建好商品提审后会出现在这里。",

  kpiPresale: "预售中商品",
  kpiOversell: "超卖商品",
  kpiOversellSub: "需人工决定补货还是退单",
  kpiOversellNone: "无超卖",
  kpiOversellQty: "超卖件数",
  stockNotice:
    "超卖只报警不自动处置：补货还是退单要人判断，自动关单会把还能补上的团也关掉。截单时间必须早于到货时间 —— 否则货到了还能继续下单，必然超卖。",
  emptyStock: "当前没有预售商品。生鲜的预售额度与截单时间在这里配。",

  btnReject: "驳回",
  btnApprove: "通过并上架",
  btnForceOff: "强制下架",
  fieldCreatedAt: "创建时间",
  fieldPricing: "多市场定价（B6：各市场分别定价）",
  priceMissingBlocking: "缺价，无法上架",
  fieldI18nCopy: "三语文案（P-3.2.5）",
  i18nFallbackLong: "缺失，按回落规则展示中文",
  fieldRejectReason: "驳回原因",
  fieldForceOffReason: "强制下架原因",
  reasonPlaceholder: "商家会原样看到：写清楚哪里不合规、怎么改",
  fieldHandledReason: "处理原因",

  presaleTitle: "预售配置",
  save: "保存",
  fieldQuota: "预售额度",
  quotaHint: "0 表示不做预售，只卖现货库存。",
  fieldCutoff: "截单时间（ISO）",
  cutoffHint: "必须早于到货时间，否则货到了还能继续下单。",
};

const en: typeof zh = {
  tabCategories: "Category tree",
  tabSkus: "Product pool",
  tabStock: "Stock & presale",

  marketCN: "China",
  marketSG: "Singapore",

  toastApproved: "Approved — the product is live",
  toastRejected: "Rejected — the reason has been sent to the merchant",
  toastForcedOff: "Taken down",
  toastPresaleSaved: "Presale settings saved",
  toastCatArchived: "Category archived",

  catOnSale: " ({n} on sale)",
  detail: "Details",

  colSkuNo: "SKU code",
  colTitle: "Product",
  colMerchant: "Merchant",
  colCategory: "Category",
  colPricing: "Per-market pricing",
  priceMissing: "{m} has no price",
  colI18n: "Translations",
  i18nMissing: "Missing {langs}",
  i18nComplete: "Complete",
  colStock: "Stock",
  colStatus: "Status",
  colActions: "Actions",
  actionAudit: "Review",
  actionView: "View",

  colPresaleQuota: "Presale quota",
  colSold: "Sold",
  colCutoffAt: "Order cut-off",
  colArriveAt: "Arrival",
  actionEditPresale: "Edit settings",

  readOnlyWhat: "product review & takedown",
  readOnlyNote: "cannot approve, reject or take down",

  catTreeTitle: "Three-level category tree",
  catTreeNotice:
    "Three levels at most. Archiving is checked first: a category that still has child categories or products on sale cannot be archived — otherwise the C-end tree grows a dead branch.",
  loading: "Loading…",
  emptyTree: "No categories yet. The category tree is the skeleton products hang from; without it merchants cannot submit anything for review.",
  catDetailTitle: "Category details",
  catDetailHint: "Pick “Details” on any category at the left to see its template, required credentials and translated names.",
  fieldCatNo: "Category no.",
  fieldLevel: "Level",
  levelN: "Level {n}",
  fieldTemplate: "Attribute template",
  fieldQualification: "Required credentials",
  requiredCode: "Validation code: {code}",
  noQualification: "None required",
  fieldI18nName: "Translated names",
  i18nFallback: "Missing (falls back to Chinese)",
  fieldSkuCount: "Products on sale",
  confirmArchiveTitle: "Archive category {name}",
  confirmArchiveDesc:
    "Once archived the category disappears from the C-end tree; if it still has child categories or products on sale, the server rejects it.",
  confirmArchiveOk: "Archive",
  btnArchiveCat: "Archive this category",

  searchSku: "Search SKU code / title / merchant",
  filterStatus: "Filter by status",
  filterStatusAll: "All statuses",
  emptySku: "No products match these filters. They appear here once merchants create and submit them in the B-end app.",

  kpiPresale: "Products on presale",
  kpiOversell: "Oversold products",
  kpiOversellSub: "Someone must choose: restock or refund",
  kpiOversellNone: "None oversold",
  kpiOversellQty: "Units oversold",
  stockNotice:
    "Overselling raises an alert but is never handled automatically: restocking versus refunding is a judgement call, and auto-cancelling would close group buys that could still have been fulfilled. The cut-off must come before the arrival time — otherwise orders keep coming in after the goods land, which guarantees overselling.",
  emptyStock: "No products on presale. Presale quotas and cut-off times for fresh produce are configured here.",

  btnReject: "Reject",
  btnApprove: "Approve & publish",
  btnForceOff: "Take down",
  fieldCreatedAt: "Created at",
  fieldPricing: "Per-market pricing (B6: priced separately per market)",
  priceMissingBlocking: "No price — cannot publish",
  fieldI18nCopy: "Copy in three languages (P-3.2.5)",
  i18nFallbackLong: "Missing — Chinese is shown per the fallback rule",
  fieldRejectReason: "Rejection reason",
  fieldForceOffReason: "Takedown reason",
  reasonPlaceholder: "The merchant sees this verbatim: say what breaks the rules and how to fix it",
  fieldHandledReason: "Reason recorded",

  presaleTitle: "Presale settings",
  save: "Save",
  fieldQuota: "Presale quota",
  quotaHint: "0 means no presale — only stock on hand is sold.",
  fieldCutoff: "Order cut-off (ISO)",
  cutoffHint: "Must be before the arrival time, otherwise orders keep coming in after the goods land.",
};

export const PRODUCTS_COPY: PageCopy<typeof zh> = { zh, en };
