// 评价治理文案（矩阵 P-7.1 / P-7.2）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {

  riskyOnly: "仅看命中刷评信号",

  toastPassed: "已通过，评价对外展示",
  toastTakenDown: "已下架，不计入评分",
  toastAppealUpheld: "申诉成立，原评价已下架",
  toastAppealDismissed: "已驳回申诉，评价维持展示",
  toastScoreSaved: "评分参数已保存",

  colReviewNo: "评价号",
  colMerchant: "商家",
  colScore: "评分",
  /** `{n}` 是分值 */
  scoreTitle: "{n} 分",
  colContent: "内容",
  colImages: "图片",
  colRiskFlags: "刷评信号",
  colTime: "时间",
  colStatus: "状态",
  colActions: "操作",
  actionAudit: "审核",
  actionView: "查看",
  actionDecide: "裁决",

  colAppealNo: "申诉单号",
  colRelatedReview: "关联评价",
  colReason: "申诉理由",
  colEvidence: "举证",
  colSubmittedAt: "提交时间",

  readOnlyWhat: "评价审核与申诉裁决",
  readOnlyNote: "不能通过、下架或裁决",
  notice:
    "刷评信号（同设备 / 同 IP / 文案雷同 / 短时集中）是「给人审的线索」，不是判定结论 —— 命中三条以上的通常值得先看，但仍要逐条读内容再决定。",
  searchAudit: "搜索评价号 / 商家 / 内容",
  searchAppeal: "搜索申诉单号 / 商家 / 理由",
  filterStatus: "按状态筛选",
  filterStatusAll: "全部状态",
  filterRisky: "刷评信号筛选",
  filterRiskyAll: "不限信号",
  // 先发后审：评价发表即展示，这里是**事后**巡查与下架，不是放行闸口。
  // 原文案写着「用户发表新评价后会重新出现在这里」—— 而默认筛的 PENDING
  // 后端从来不产生，那句话永远不会兑现。
  emptyAudit: "还没有评价。评价发表后即对买家展示，这里做事后巡查与下架。",
  emptyAppeals: "没有待裁决的申诉。商家对差评有异议时会从 B 端提交到这里。",

  scoreTitleCard: "评分算法参数",
  scoreReadOnlyWhat: "评分算法参数",
  scoreReadOnlyNote: "不能调整权重与保护期",
  scoreNotice:
    "改这些参数会改变「历史评价的呈现」（时效衰减是实时计算的）。影响预览要等有真实数据后再做 —— 在那之前，改动请配合公告。",
  weightProduct: "商品分权重",
  weightFulfill: "履约分权重",
  weightService: "服务分权重",
  /** `{sum}` / `{total}` 是权重和与要求值 */
  weightSum: "三维权重之和：{sum} / {total}",
  weightSumBad: "（不等于 100 无法保存）",
  fieldProtect: "新商家保护期（天）",
  protectHint: "期内不展示均分，避免小商家开张第一单遇到差评就被判死。",
  fieldDecay: "时效衰减半衰期（天）",
  decayHint: "越久远的评价权重越低，让评分反映当前而不是历史。",

  /** `{n}` 是分值 */
  drawerScore: "{n} 分",
  btnTakeDown: "下架",
  btnPass: "通过",
  fieldScoreProduct: "商品分",
  fieldScoreFulfill: "履约分",
  fieldScoreService: "服务分",
  fieldOrder: "关联订单",
  fieldContent: "评价内容",
  fieldImages: "图片",
  /** `{n}` 是图片数 */
  imagesCount: "{n} 张（mock 环境不展示实图）",
  none: "无",
  fieldTakeDownReason: "下架原因",
  takeDownPlaceholder: "用户与商家都会看到，写清楚违反了哪条规范",

  /** `{name}` 是商家名 */
  drawerAppeal: "{name} 的申诉",
  btnKeepReview: "维持原评价",
  btnUphold: "申诉成立（下架差评）",
  fieldAppealReason: "申诉理由",
  fieldEvidence: "举证材料",
  /** `{n}` 是举证项数 */
  evidenceCount: "{n} 项",
  fieldVerdict: "裁决说明",
  verdictPlaceholder: "支持与驳回都要写：商家会原样看到。「已读不处理」不是一种结果",
};

const en: typeof zh = {

  riskyOnly: "Flagged reviews only",

  toastPassed: "Approved — the review is now public",
  toastTakenDown: "Taken down — it no longer counts toward the rating",
  toastAppealUpheld: "Appeal upheld — the original review is taken down",
  toastAppealDismissed: "Appeal dismissed — the review stays public",
  toastScoreSaved: "Rating parameters saved",

  colReviewNo: "Review no.",
  colMerchant: "Merchant",
  colScore: "Rating",
  scoreTitle: "{n} out of 5",
  colContent: "Content",
  colImages: "Photos",
  colRiskFlags: "Fake-review signals",
  colTime: "Time",
  colStatus: "Status",
  colActions: "Actions",
  actionAudit: "Review",
  actionView: "View",
  actionDecide: "Decide",

  colAppealNo: "Appeal no.",
  colRelatedReview: "Review",
  colReason: "Grounds",
  colEvidence: "Evidence",
  colSubmittedAt: "Submitted at",

  readOnlyWhat: "review moderation & appeals",
  readOnlyNote: "cannot approve, take down or decide appeals",
  notice:
    "Fake-review signals (same device / same IP / duplicate wording / burst in time) are leads for a human, not a verdict — three or more usually deserves a look first, but you still have to read each one before deciding.",
  searchAudit: "Search review no. / merchant / content",
  searchAppeal: "Search appeal no. / merchant / grounds",
  filterStatus: "Filter by status",
  filterStatusAll: "All statuses",
  filterRisky: "Signal filter",
  filterRiskyAll: "Any signal state",
  emptyAudit: "No reviews yet. Reviews go live on publish; this page is for post-hoc review and take-down.",
  emptyAppeals: "No appeals to decide. Merchants submit them from the B-end app when they dispute a bad review.",

  scoreTitleCard: "Rating algorithm parameters",
  scoreReadOnlyWhat: "rating algorithm parameters",
  scoreReadOnlyNote: "cannot change weights or the protection period",
  scoreNotice:
    "Changing these changes how existing reviews are presented (time decay is computed live). An impact preview has to wait for real data — until then, pair any change with an announcement.",
  weightProduct: "Product score weight",
  weightFulfill: "Fulfillment score weight",
  weightService: "Service score weight",
  weightSum: "Weights total: {sum} / {total}",
  weightSumBad: " (must equal 100 to save)",
  fieldProtect: "New-merchant protection (days)",
  protectHint: "No average is shown during this window, so a small shop is not written off by one bad review on day one.",
  fieldDecay: "Time-decay half-life (days)",
  decayHint: "Older reviews weigh less, so the rating reflects the present rather than the past.",

  drawerScore: "{n} out of 5",
  btnTakeDown: "Take down",
  btnPass: "Approve",
  fieldScoreProduct: "Product score",
  fieldScoreFulfill: "Fulfillment score",
  fieldScoreService: "Service score",
  fieldOrder: "Order",
  fieldContent: "Review text",
  fieldImages: "Photos",
  imagesCount: "{n} (images are not rendered in the mock environment)",
  none: "None",
  fieldTakeDownReason: "Take-down reason",
  takeDownPlaceholder: "Both the customer and the merchant see this — say which guideline was broken",

  drawerAppeal: "Appeal from {name}",
  btnKeepReview: "Keep the review",
  btnUphold: "Uphold appeal (take the review down)",
  fieldAppealReason: "Grounds for appeal",
  fieldEvidence: "Evidence",
  evidenceCount: "{n} item(s)",
  fieldVerdict: "Decision note",
  verdictPlaceholder: "Write one either way — the merchant sees it verbatim. “Read and ignored” is not an outcome",
};

export const REVIEWS_COPY: PageCopy<typeof zh> = { zh, en };
