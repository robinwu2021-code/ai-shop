// 平台活动与报名 —— B 端替身（原型 s27 · s28）。
//
// 两个示例活动：一个还能报（中秋满减，平台出一半），一个已经结束。
// 报名的校验与后端同一口径：过了截止拒、已通过的不能改、只能撤待审的。
import { ApiError } from "@shared/net/http-client";
import { db, delay } from "@shared/mock/db";
import type { PlatformActivity, PlatformEnrollment } from "@shared/types";
import type { MerchantApi } from "../contract";

const DAY = 86_400_000;
const now = Date.now();

function perPlatform(a: PlatformActivity): number {
  return Math.floor(((a.benefitAmountMinor ?? 0) * a.platformShareBp) / 10_000);
}

const activities: PlatformActivity[] = [
  {
    activityNo: "PT-PLAT-1", name: "中秋大促", triggerType: "AMOUNT", triggerAmountMinor: 9900, triggerQty: null,
    benefitType: "CUT", benefitAmountMinor: 2000, startAt: now + 6 * DAY, endAt: now + 16 * DAY,
    enrollDeadline: now + 2 * DAY, platformShareBp: 5000, budgetMinor: 5_000_000, reservedMinor: 1_840_000,
    perOrderPlatformMinor: 1000, perOrderMerchantMinor: 1000,
    enrollRule: { minRating: 4.5, noViolation: true, categoryNos: [], cityCodes: [] },
    status: "RUNNING", submitted: 0, approved: 0, rejected: 0, mine: null,
  },
  {
    activityNo: "PT-PLAT-0", name: "开学季满减", triggerType: "AMOUNT", triggerAmountMinor: 5900, triggerQty: null,
    benefitType: "CUT", benefitAmountMinor: 1000, startAt: now - 20 * DAY, endAt: now - 5 * DAY,
    enrollDeadline: now - 22 * DAY, platformShareBp: 10000, budgetMinor: 2_000_000, reservedMinor: 800_000,
    perOrderPlatformMinor: 1000, perOrderMerchantMinor: 0,
    enrollRule: { minRating: null, noViolation: false, categoryNos: [], cityCodes: [] },
    status: "ENDED", submitted: 0, approved: 0, rejected: 0, mine: null,
  },
];

function tabOf(a: PlatformActivity): string {
  if (a.status === "ENDED" || (a.endAt ?? 0) < Date.now()) return "ENDED";
  const live = a.mine && (a.mine.status === "SUBMITTED" || a.mine.status === "APPROVED");
  if (live) return "ENROLLED";
  return (a.enrollDeadline ?? 0) >= Date.now() ? "ENROLLABLE" : "ENDED";
}

function find(activityNo: string): PlatformActivity {
  const a = activities.find((x) => x.activityNo === activityNo);
  if (!a) throw new ApiError(10404, "活动不存在");
  return a;
}

export const platformMock: Pick<MerchantApi,
  "mPlatformActivities" | "mPlatformActivity" | "mEnroll" | "mWithdrawEnrollment"
> = {
  async mPlatformActivities(tab) {
    return delay(activities.filter((a) => !tab || tabOf(a) === tab).map((a) => ({ ...a })));
  },

  async mPlatformActivity(activityNo) {
    return delay({ ...find(activityNo) });
  },

  async mEnroll(activityNo, req) {
    const a = find(activityNo);
    if (a.status !== "RUNNING" || (a.enrollDeadline ?? 0) < Date.now()) {
      throw new ApiError(40031, "报名已截止或不满足报名门槛");
    }
    if (!req.goodsNos.length || req.quota <= 0) throw new ApiError(10400, "请选择商品并填写份数");
    if (a.mine?.status === "APPROVED") throw new ApiError(10409, "已通过的报名不能再改");
    const per = perPlatform(a);
    const e: PlatformEnrollment = {
      enrollmentNo: a.mine?.enrollmentNo ?? `PE-${Date.now()}`,
      activityNo, entityNo: db.merchant.merchantNo, merchantName: db.merchant.name,
      goodsNos: [...req.goodsNos], quota: req.quota, quotaUsed: 0,
      platformMaxMinor: per * req.quota,
      merchantMaxMinor: ((a.benefitAmountMinor ?? 0) - per) * req.quota,
      rating: 4.8, status: "SUBMITTED", rejectReason: null, reviewedAt: null, createdAt: Date.now(),
    };
    a.mine = e;
    return delay({ ...e });
  },

  async mWithdrawEnrollment(activityNo) {
    const a = find(activityNo);
    if (!a.mine || a.mine.status !== "SUBMITTED") throw new ApiError(10409, "只能撤回待审的报名");
    a.mine = { ...a.mine, status: "WITHDRAWN" };
    return delay({ ...a.mine });
  },
};
