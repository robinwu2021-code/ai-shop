import { expect, it } from "vitest";
import { pointsEventLabel, pointsMetricLabel, pointsMetricUnit, pointsReasonLabel } from "./points-display";
const zh=(s:string)=>s;const en=(_s:string,e:string)=>e;
it("labels all queue kinds and falls back without displaying raw enums",()=>{
 for(const key of ["SOURCE_FACT","EARN_GRANT","REFUND_CONFIRMATION","REFUND_CLOSURE","ZERO_CASH","EXPIRY_REMINDER","OPS_ATTENTION","FUNDS_RECOVERY"]) expect(pointsEventLabel(key,zh)).not.toBe(key);
 expect(pointsEventLabel("FUTURE",zh)).toBe("其他处理事件");expect(pointsEventLabel("EARN_GRANT",en)).toBe("Order reward posting");
});
it("keeps points metrics separate from unknown units",()=>{
 expect(pointsMetricLabel("refund_returnedPoints",zh)).toBe("退款退回积分");expect(pointsMetricUnit("redeemed_points",zh)).toBe("积分");
 expect(pointsMetricUnit("future_minor",zh)).toBe("单位待核实");
});
it("explains known failures and safely handles unknown or absent reasons",()=>{
 expect(pointsReasonLabel("java: POINTS_POOL_INSUFFICIENT",zh)).toContain("预算不足");
 expect(pointsReasonLabel("INTERNAL_ERROR",zh)).toContain("原始信息");expect(pointsReasonLabel(null,zh)).toBe("—");
});
