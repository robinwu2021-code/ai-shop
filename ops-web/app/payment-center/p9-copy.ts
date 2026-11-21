// 支付中心 P9 文案：渠道账户改坐标、沙箱试运行资质、终端凭证引用与退役、应用撤销门店授权、告警指标与冲正回查、储值交易详情（TDD-支付中心运营页面 §2.15）。繁体由 zh 转换。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  // 渠道账户改坐标
  accountEdit: "修改坐标",
  accountEditTitle: "修改渠道账户坐标",
  accountEditNote: "只有未核验的账户能改；改完核验状态退回「待核验」，需要重新提交核验。渠道商户号在详情里是脱敏的，这里要重新完整填写。",
  channelMerchantId: "渠道商户号",
  subMerchantId: "子商户号",
  accountMode: "账户模式",
  fundsPath: "资金路径",
  parentPayMerchantNo: "上级平台账户",
  parentHelp: "自有商户模式留空；服务商 / 平台子商户模式必填，须是同渠道同市场的平台账户",
  evidenceRef: "证明文件",
  evidenceHelp: "格式 file:路径；手工登记的账户必填",
  mode_MERCHANT_OWN: "自有商户",
  mode_SERVICE_PROVIDER: "服务商",
  mode_PLATFORM_SUB: "平台子商户",
  path_DIRECT: "直连",
  path_AGGREGATED: "聚合",

  // 沙箱试运行资质
  qualPrepare: "准备沙箱试运行",
  qualPrepareTitle: "准备沙箱试运行资质",
  qualPrepareNote: "只适用于蓝新测试渠道（NEWEBPAY_TEST）账户。建出的是草稿，要经「渠道资质」审批才生效；有效期最长 24 小时。",
  evidenceNo: "证据号",
  revision: "版本号",
  revisionHelp: "必须是现有最大版本号 + 1",
  protocolEvidence: "协议证据",
  trialEvidence: "试运行证据",
  trialHelp: "以 sandbox-trial: 开头",
  enablementEvidence: "开通证据",
  returnUrl: "回跳地址",
  notifyUrl: "通知地址",
  notifyHelp: "路径须为 /pay/callback/newebpay/pay/{账户号}，与回跳地址同主机同端口",
  validUntil: "有效期至",
  validUntilHelp: "未来 24 小时内，格式 YYYY-MM-DD HH:mm",
  qualDetail: "详情",
  qualPayload: "资质内容",
  qualCoordinates: "坐标",

  // 终端凭证与退役
  sectionCredRetire: "凭证与退役",
  credentialRef: "凭证引用",
  keyId: "密钥编号",
  retirementStatus: "退役状态",
  noCredential: "未绑定凭证",
  credNoPerm: "查看凭证引用需要「凭证：查看」权限",
  retire_NOT_STARTED: "未开始",
  retire_PENDING: "处理中",
  retire_RECOVERY: "恢复中",
  retire_RETIRED: "已退役",
  retire_REVOKED: "已撤销",

  // 应用撤销门店授权
  grantRevoke: "撤销",
  /** `{store}` 门店号 */
  grantRevokeTitle: "撤销门店 {store} 的授权",
  grantRevokeDesc: "撤销后这个应用不能再代这家门店发起交易；已撤销的再点一次不会有变化。",

  // 告警
  metricCatalog: "指标说明",
  /** `{unit}` 单位，`{window}` 默认窗口秒数，`{scopes}` 适用范围 */
  metricHelp: "单位 {unit}，默认窗口 {window} 秒，适用范围 {scopes}",
  eventDriven: "事件触发",
  recheckReversal: "冲正回查",
  recheckReversalTitle: "向渠道回查这笔冲正",
  recheckReversalDesc: "只对未关闭的「冲正待确认」告警可用；需要是认领人或有升级权限。回查结果是「待确认」时告警会重新提醒。",
  /** `{result}` 回查结果 */
  recheckResult: "回查结果：{result}",
  recheck_VERIFIED: "已确认",
  recheck_PENDING: "仍待确认",
  recheck_DEFERRED: "稍后再查",

  // 储值交易
  createdAt: "记录时间",
  accountSeq: "账户流水序号",
  issuerNo: "发行方",
};

const en: typeof zh = {
  accountEdit: "Edit coordinates",
  accountEditTitle: "Edit channel account coordinates",
  accountEditNote: "Only unverified accounts can be edited; verification goes back to Pending and must be resubmitted. The channel merchant ID is masked in details, so enter it in full here.",
  channelMerchantId: "Channel merchant ID",
  subMerchantId: "Sub-merchant ID",
  accountMode: "Account mode",
  fundsPath: "Funds path",
  parentPayMerchantNo: "Parent platform account",
  parentHelp: "Leave blank for merchant-own; required for service-provider / platform-sub, and must be a platform account on the same channel and market",
  evidenceRef: "Evidence file",
  evidenceHelp: "Format file:path; required for manually registered accounts",
  mode_MERCHANT_OWN: "Merchant own",
  mode_SERVICE_PROVIDER: "Service provider",
  mode_PLATFORM_SUB: "Platform sub-merchant",
  path_DIRECT: "Direct",
  path_AGGREGATED: "Aggregated",

  qualPrepare: "Prepare sandbox trial",
  qualPrepareTitle: "Prepare sandbox trial qualification",
  qualPrepareNote: "Only for NewebPay test-channel (NEWEBPAY_TEST) accounts. Creates a draft that takes effect after Channel qualification approval; valid for at most 24 hours.",
  evidenceNo: "Evidence no.",
  revision: "Revision",
  revisionHelp: "Must be the current highest revision + 1",
  protocolEvidence: "Protocol evidence",
  trialEvidence: "Trial evidence",
  trialHelp: "Starts with sandbox-trial:",
  enablementEvidence: "Enablement evidence",
  returnUrl: "Return URL",
  notifyUrl: "Notify URL",
  notifyHelp: "Path must be /pay/callback/newebpay/pay/{account no.}, same host and port as the return URL",
  validUntil: "Valid until",
  validUntilHelp: "Within the next 24 hours, format YYYY-MM-DD HH:mm",
  qualDetail: "Details",
  qualPayload: "Qualification payload",
  qualCoordinates: "Coordinates",

  sectionCredRetire: "Credential and retirement",
  credentialRef: "Credential reference",
  keyId: "Key ID",
  retirementStatus: "Retirement",
  noCredential: "No credential bound",
  credNoPerm: "Viewing the credential reference requires the Credential: view permission",
  retire_NOT_STARTED: "Not started",
  retire_PENDING: "In progress",
  retire_RECOVERY: "Recovering",
  retire_RETIRED: "Retired",
  retire_REVOKED: "Revoked",

  grantRevoke: "Revoke",
  grantRevokeTitle: "Revoke the grant for store {store}",
  grantRevokeDesc: "The app can no longer transact for this store; revoking an already revoked grant changes nothing.",

  metricCatalog: "Metric reference",
  metricHelp: "Unit {unit}, default window {window}s, scopes {scopes}",
  eventDriven: "Event-driven",
  recheckReversal: "Recheck reversal",
  recheckReversalTitle: "Recheck this reversal with the channel",
  recheckReversalDesc: "Only for open Reversal pending alerts; you must be the claimant or hold escalation permission. A Pending result re-raises the alert.",
  recheckResult: "Recheck result: {result}",
  recheck_VERIFIED: "Verified",
  recheck_PENDING: "Still pending",
  recheck_DEFERRED: "Check later",

  createdAt: "Recorded at",
  accountSeq: "Account sequence",
  issuerNo: "Issuer",
};

export const P9_COPY: PageCopy<typeof zh> = { zh, en };
export type P9Copy = typeof zh;
