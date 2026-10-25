// Presentation metadata only: ledger values and source identifiers stay unchanged.
const events: Record<string, [string, string]> = {
 SOURCE_FACT:["订单事实同步","Order fact synchronization"], EARN_GRANT:["订单赠送入账","Order reward posting"],
 REFUND_CONFIRMATION:["退款确认","Refund confirmation"], REFUND_CLOSURE:["退款关闭处理","Refund closure"],
 ZERO_CASH:["零现金订单处理","Zero-cash order"], EXPIRY_REMINDER:["到期提醒","Expiry reminder"],
 OPS_ATTENTION:["运营待处理","Operator attention"], FUNDS_RECOVERY:["资金恢复","Funds recovery"],
};
const metrics: Record<string, [string, string]> = {
 granted_points:["已赠送积分","Granted points"], redeemed_points:["已核销积分","Redeemed points"],
 refund_returnedPoints:["退款退回积分","Points returned on refund"], expired_points:["已到期积分","Expired points"],
};
const reasons: Record<string, [string, string]> = {
 POINTS_POOL_INSUFFICIENT:["平台预算不足，请补足预算后重试","Platform budget insufficient; replenish before retrying"],
 POINTS_TYPE_NOT_ACTIVE:["积分类型当前不允许此操作","The points type does not allow this operation"],
 POINTS_SELF_APPROVE:["申请与复核必须由不同人员完成","A different operator must review the request"],
};
type Text = (zh: string, english: string) => string;
export const pointsEventLabel = (key: string, t: Text) => events[key] ? t(...events[key]) : t("其他处理事件", "Other processing event");
export const pointsMetricLabel = (key: string, t: Text) => metrics[key] ? t(...metrics[key]) : t("其他统计指标", "Other metric");
export const pointsMetricUnit = (key: string, t: Text) => metrics[key] ? t("积分", "points") : t("单位待核实", "Unit unverified");
export function pointsReasonLabel(reason: string | null | undefined, t: Text) {
 if (!reason) return "—";
 const key = Object.keys(reasons).find(key => reason.includes(key));
 return key ? t(...reasons[key]) : t("处理异常，请查看原始信息", "Processing exception; inspect the original details");
}
