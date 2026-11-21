// 支付中心 · 台湾主流渠道运营（《支付中心-台湾主流渠道运营需求》）：渠道快捷筛选、渠道健康卡片、蓝新卡退款提示。繁体由 zh 转换。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  channelFilter: "全部渠道",
  ch_NEWEBPAY: "蓝新",
  ch_LINEPAY: "LINE Pay",
  ch_JKOPAY: "街口",
  healthTitle: "台湾主流渠道 · 近 1 小时",
  successRate: "调用成功率",
  /** `{ok}` 成功笔数，`{all}` 总笔数 */
  successDetail: "{ok} / {all} 笔",
  noCalls: "近 1 小时没有调用",
  openAlerts: "未处理告警",
  expiringKeys: "30 天内到期密钥",
  viewCalls: "看调用",
  viewAlerts: "看告警",
  viewKeys: "看密钥",
  healthUnavailable: "暂时取不到",
  refundDispatchedNote: "「已发往渠道」的退款在等渠道回结果。蓝新信用卡退款目前缺渠道的可信完成证明，需要到蓝新后台核对：打开退款详情里的订单，看渠道与流水，必要时在订单里「补查」。人工确认完成需要 near-pay 补接口，暂不能在这里标记。",
};

const en: typeof zh = {
  channelFilter: "All channels",
  ch_NEWEBPAY: "NewebPay",
  ch_LINEPAY: "LINE Pay",
  ch_JKOPAY: "JKOPay",
  healthTitle: "Taiwan mainstream channels · last hour",
  successRate: "Call success rate",
  successDetail: "{ok} / {all} calls",
  noCalls: "No calls in the last hour",
  openAlerts: "Open alerts",
  expiringKeys: "Keys expiring in 30 days",
  viewCalls: "Calls",
  viewAlerts: "Alerts",
  viewKeys: "Keys",
  healthUnavailable: "Unavailable",
  refundDispatchedNote: "Dispatched refunds are waiting for the channel's result. NewebPay card refunds currently lack a trusted completion proof from the channel, so verify them in the NewebPay back office: open the order from the refund detail, check the channel and trade records, and use Recheck on the order if needed. Marking them complete manually needs a near-pay endpoint and is not available here yet.",
};

export const TW_COPY: PageCopy<typeof zh> = { zh, en };
export type TwCopy = typeof zh;
