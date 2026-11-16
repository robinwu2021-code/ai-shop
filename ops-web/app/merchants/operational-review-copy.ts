const zh = {
  title: "经营审核", hint: "经营开通审核与法人入驻、支付进件分别处理。套餐履约成功后仅开通对应门店。",
  unknown: "未提交经营审核", pending: "待开通", approved: "已通过", rejected: "已驳回",
  submit: "提交审核", submitHint: "提交后暂停经营，仍可办理套餐。符合条件的套餐履约完成或人工审核通过后开通。现有免费建店规则保持不变。",
  approve: "审核通过", reject: "驳回申请", manual: "人工审核", store: "门店套餐", entity: "主体套餐",
  order: "履约订单", source: "审核来源", reasonHint: "请说明原因（会记入平台审计）", failed: "经营审核读取失败", retry: "重试",
};
const en: typeof zh = {
  title: "Operating review", hint: "Operating admission is separate from legal and payment onboarding. Successful package fulfillment opens only the target store.",
  unknown: "No operating review submitted", pending: "Awaiting activation", approved: "Approved", rejected: "Rejected",
  submit: "Submit review", submitHint: "Submission pauses store operations while package purchases remain available. A qualifying package fulfillment or manual approval opens this store. Existing free-store rules remain unchanged.",
  approve: "Approve", reject: "Reject", manual: "Manual review", store: "Store package", entity: "Entity package",
  order: "Fulfillment order", source: "Review source", reasonHint: "Reason (recorded in the platform audit log)", failed: "Could not load operating review", retry: "Retry",
};
const zhTW: typeof zh = {
  title: "經營審核", hint: "經營開通審核與法人入駐、支付進件分別處理。方案履約成功後僅開通對應門市。",
  unknown: "未提交經營審核", pending: "待開通", approved: "已通過", rejected: "已駁回",
  submit: "提交審核", submitHint: "提交後暫停經營，仍可辦理方案。符合條件的方案履約完成或人工審核通過後開通。現有免費建店規則保持不變。",
  approve: "審核通過", reject: "駁回申請", manual: "人工審核", store: "門市方案", entity: "主體方案",
  order: "履約訂單", source: "審核來源", reasonHint: "請說明原因（會記入平台稽核）", failed: "經營審核讀取失敗", retry: "重試",
};
export const STORE_REVIEW_COPY = { zh, en, "zh-TW": zhTW };
