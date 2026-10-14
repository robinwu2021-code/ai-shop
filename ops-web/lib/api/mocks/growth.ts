// 覆盖范围：归因与裂变（P-9）。
import * as db from "@/lib/mock/db";
import { ATTR_SOURCES, ATTR_WINDOW_MAX, ATTR_WINDOW_MIN, type FissionCampaign } from "@/lib/types";
import type { GrowthApi } from "../contracts/growth";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

export const growthMock: GrowthApi = {
  getAttributionRule: async () => wait(db.attributionRule),

  saveAttributionRule: async (v) => {
    // 半个优先级表在冲突时会随机裁决 —— 所以要求全序：三个来源不重不漏
    const unique = new Set(v.priority);
    if (unique.size !== ATTR_SOURCES.length || ATTR_SOURCES.some((s) => !unique.has(s))) {
      fail(`归因优先级必须覆盖全部 ${ATTR_SOURCES.length} 个来源且不重复（当前 ${v.priority.join(" > ") || "空"}）`, `Attribution priority must cover all ${ATTR_SOURCES.length} sources with no repeats (currently ${v.priority.join(" > ") || "empty"})`);
    }
    if (v.windowDays < ATTR_WINDOW_MIN || v.windowDays > ATTR_WINDOW_MAX) {
      fail(`归因窗口期需在 ${ATTR_WINDOW_MIN}–${ATTR_WINDOW_MAX} 天之间（0 天等于关掉归因）`, `The attribution window must be ${ATTR_WINDOW_MIN}–${ATTR_WINDOW_MAX} days (0 days switches attribution off)`);
    }
    // 一个因子都不选 = 所有人都是新客，新人券会被无限领
    if (v.newUserFactors.length === 0) fail("新客判定至少要选一个因子（设备 / 手机号）", "New-customer detection needs at least one signal (device or phone number)");
    Object.assign(db.attributionRule, v, { updatedAt: "2026-08-06T00:00:00Z", updatedBy: "admin" });
    return wait(db.attributionRule, 400);
  },

  listAttributionTraces: (q = {}) =>
    wait(
      db.paginate(db.attributionTraces, q.page, q.size, (t) =>
        db.eqHit(q.source, t.source) &&
        (q.conflictOnly !== "1" || !!t.conflictWith) &&
        // riskSignals 后端不下发（契约里已改成可选）—— mock 里也别假设它一定在
        (q.riskyOnly !== "1" || (t.riskSignals?.length ?? 0) > 0) &&
        db.kwHit(q.keyword, t.traceNo, t.userNickname ?? t.userNo, t.sourceRef, t.orderNo),
      ),
    ),

  listFissionCampaigns: (q = {}) =>
    wait(db.paginate(db.fissionCampaigns, q.page, q.size, (f) => db.kwHit(q.keyword, f.fissionNo, f.name))),

  saveFissionCampaign: async (v) => {
    // ADR-004：去团长化后不存在现金激励。发现金 = 职业薅羊毛立刻回来，
    // 且归因作弊有了直接变现路径。类型上只有 COUPON，这里再挡一次运行时传入。
    if (v.rewardType !== "COUPON") fail("邀请有礼只能发券，不能发现金（ADR-004）", "Referral rewards are coupons only, never cash (ADR-004)");
    if (v.inviterCount < 0 || v.inviteeCount < 0) fail("奖励张数不能为负", "Reward counts cannot be negative");
    if (v.inviterCount + v.inviteeCount === 0) fail("邀请人与被邀请人至少一方有奖励", "At least one side — referrer or referee — has to get something");
    const coupon = db.coupons.find((c) => c.couponNo === v.couponNo);
    if (!coupon) notFound("券模板", "Coupon template", v.couponNo);
    const saved = db.upsert<FissionCampaign>(
      db.fissionCampaigns,
      { ...v, enabled: false, invitedCount: 0, convertedCount: 0, createdAt: "2026-08-06T00:00:00Z" },
      "fissionNo",
      () => db.nextNo("FS", db.fissionCampaigns, 9000, "fissionNo"),
    );
    return wait(saved, 400);
  },

  setFissionEnabled: async (fissionNo, enabled) => {
    const f = db.fissionCampaigns.find((x) => x.fissionNo === fissionNo);
    if (!f) notFound("裂变活动", "Referral campaign", fissionNo);
    f.enabled = enabled;
    return wait(f, 400);
  },
};
