// 运营侧会员与新模型营销（P8）。
import * as db from "@/lib/mock/db";
import { paginate } from "@/lib/mock/db/helpers";
import { wait } from "./_wait";
import type { OpsEnrollment, OpsMember, OpsPlatformActivity } from "@/lib/types";
import type { MemberApi } from "../contracts/member";

// 平台活动与报名（s29 · s30）的替身。校验与后端同一口径：占预算超了拒、只有待审的能审、驳回要理由
const DAY = 86_400_000;
const platformActivities: OpsPlatformActivity[] = [{
  activityNo: "PT-PLAT-1", name: "中秋大促", triggerType: "AMOUNT", triggerAmountMinor: 9900, triggerQty: null,
  benefitType: "CUT", benefitAmountMinor: 2000, startAt: Date.now() + 6 * DAY, endAt: Date.now() + 16 * DAY,
  enrollDeadline: Date.now() + 2 * DAY, platformShareBp: 5000, budgetMinor: 5_000_000, reservedMinor: 1_840_000,
  perOrderPlatformMinor: 1000, perOrderMerchantMinor: 1000,
  enrollRule: { minRating: 4.5, noViolation: true, categoryNos: ["生鲜", "粮油"], cityCodes: ["深圳"] },
  status: "RUNNING", submitted: 2, approved: 1, rejected: 0,
}];
const enrollments: OpsEnrollment[] = [
  { enrollmentNo: "PE-1", activityNo: "PT-PLAT-1", entityNo: "M001", merchantName: "张记粮油", goodsNos: ["G1", "G2", "G3"],
    quota: 100, quotaUsed: 0, platformMaxMinor: 100_000, merchantMaxMinor: 100_000, rating: 4.9, status: "SUBMITTED",
    rejectReason: null, reviewedAt: null, createdAt: Date.now() - DAY },
  { enrollmentNo: "PE-2", activityNo: "PT-PLAT-1", entityNo: "M002", merchantName: "鲜果直供", goodsNos: ["G4", "G5", "G6", "G7", "G8"],
    quota: 300, quotaUsed: 0, platformMaxMinor: 300_000, merchantMaxMinor: 300_000, rating: 4.7, status: "SUBMITTED",
    rejectReason: null, reviewedAt: null, createdAt: Date.now() - DAY },
  { enrollmentNo: "PE-3", activityNo: "PT-PLAT-1", entityNo: "M003", merchantName: "老王蔬菜", goodsNos: ["G9", "G10"],
    quota: 50, quotaUsed: 0, platformMaxMinor: 50_000, merchantMaxMinor: 50_000, rating: 4.6, status: "APPROVED",
    rejectReason: null, reviewedAt: Date.now() - 3600_000, createdAt: Date.now() - 2 * DAY },
];

function recount(a: OpsPlatformActivity) {
  const es = enrollments.filter((e) => e.activityNo === a.activityNo);
  a.submitted = es.filter((e) => e.status === "SUBMITTED").length;
  a.approved = es.filter((e) => e.status === "APPROVED").length;
  a.rejected = es.filter((e) => e.status === "REJECTED").length;
}

export const memberMock: MemberApi = {
  listPlatformActivities: () => wait(platformActivities.map((a) => ({ ...a }))),
  savePlatformActivity: (d) => {
    if (!d.name.trim()) return Promise.reject(new Error("请填写名称"));
    if (d.enrollDeadline > d.startAt) return Promise.reject(new Error("报名须在活动开始前截止"));
    if (d.platformShareBp > 0 && !d.budgetMinor) return Promise.reject(new Error("平台出资须设预算"));
    const per = Math.floor((d.benefitAmountMinor * d.platformShareBp) / 10_000);
    const exist = d.activityNo ? platformActivities.find((a) => a.activityNo === d.activityNo) : undefined;
    const row: OpsPlatformActivity = {
      activityNo: exist?.activityNo ?? `PT-PLAT-${platformActivities.length + 1}`,
      name: d.name.trim(), triggerType: d.triggerType, triggerAmountMinor: d.triggerAmountMinor ?? null,
      triggerQty: d.triggerQty ?? null, benefitType: "CUT", benefitAmountMinor: d.benefitAmountMinor,
      startAt: d.startAt, endAt: d.endAt, enrollDeadline: d.enrollDeadline, platformShareBp: d.platformShareBp,
      budgetMinor: d.budgetMinor ?? null, reservedMinor: exist?.reservedMinor ?? 0,
      perOrderPlatformMinor: per, perOrderMerchantMinor: d.benefitAmountMinor - per, enrollRule: d.enrollRule,
      status: d.publish ? "RUNNING" : "DRAFT", submitted: 0, approved: 0, rejected: 0,
    };
    if (exist) Object.assign(exist, row);
    else platformActivities.unshift(row);
    return wait({ ...row });
  },
  listEnrollments: (activityNo, status) =>
    wait(enrollments.filter((e) => e.activityNo === activityNo && (!status || e.status === status))),
  reviewEnrollment: (enrollmentNo, pass, reason) => {
    const e = enrollments.find((x) => x.enrollmentNo === enrollmentNo);
    if (!e) return Promise.reject(new Error("报名不存在"));
    if (e.status !== "SUBMITTED") return Promise.reject(new Error("已经审过了，刷新看看"));
    const a = platformActivities.find((x) => x.activityNo === e.activityNo)!;
    if (!pass) {
      if (!reason?.trim()) return Promise.reject(new Error("请填写驳回理由"));
      e.status = "REJECTED";
      e.rejectReason = reason.trim();
    } else {
      if (a.budgetMinor != null && a.reservedMinor + e.platformMaxMinor > a.budgetMinor) {
        return Promise.reject(new Error("通过这份报名会超出平台预算"));
      }
      a.reservedMinor += e.platformMaxMinor;
      e.status = "APPROVED";
    }
    e.reviewedAt = Date.now();
    recount(a);
    return wait({ ...e });
  },
  listOpsMembers: (q = {}) =>
    wait(paginate<OpsMember>(db.opsMembers, q.page, q.size, (m) =>
      (!q.entityNo || m.entityNo === q.entityNo)
      // **与后端同一条规矩：只接受恰好四位**。mock 放宽的话，
      // 演示时输三位能查出人，接真后端却被拒 —— 而那时没人记得是哪条拦的
      && (!q.phoneTail || (q.phoneTail.length === 4 && m.phoneTail === q.phoneTail)))),

  getOpsPerson: (personNo) =>
    wait({
      personNo,
      phoneTail: db.opsMembers.find((m) => m.personNo === personNo)?.phoneTail ?? null,
      userNo: personNo === "PS-1002" ? null : "U-" + personNo,
      memberships: db.opsMembers.filter((m) => m.personNo === personNo),
      merges: [],
    }),

  revealMemberPhone: (personNo, reason) => {
    // 与后端同一条：理由太短直接拒。「查一下」等于没有理由
    if (!reason || reason.trim().length < 4) {
      return Promise.reject(new Error("请写清查看理由（至少四个字）"));
    }
    const tail = db.opsMembers.find((m) => m.personNo === personNo)?.phoneTail ?? "0000";
    return wait({ phone: "138****" + tail });
  },

  listReachStats: () => wait(db.reachStats),
  getLevelPolicy: () => wait({ ...db.levelPolicy }),
  saveLevelPolicy: (v) => {
    // 与后端 LevelPolicy#valid 同一条：常客门槛必须低于熟客，否则「常客」整片消失
    if (v.sleepDays < 7 || v.sleepDays > 365 || v.regularD90Orders < 1
        || v.loyalD90Orders > 99 || v.regularD90Orders >= v.loyalD90Orders) {
      return Promise.reject(new Error("口径不自洽：沉睡 7–365 天，常客门槛须低于熟客门槛"));
    }
    Object.assign(db.levelPolicy, v);
    return wait({ ...db.levelPolicy });
  },
  listOpsPromoCoupons: (entityNo) =>
    wait(db.opsPromoCoupons.filter((c) => !entityNo || c.entityNo === entityNo)),
  listOpsPromoActivities: (entityNo) =>
    wait(db.opsPromoActivities.filter((a) => !entityNo || a.entityNo === entityNo)),
  stopOpsActivity: (activityNo, reason) => {
    const a = db.opsPromoActivities.find((x) => x.activityNo === activityNo);
    if (!a) return Promise.reject(new Error("活动不存在"));
    if (!reason || reason.trim().length < 4) {
      return Promise.reject(new Error("请写清停止原因（至少四个字）"));
    }
    a.status = "ENDED";
    a.endedReason = "MANUAL";
    return wait(a);
  },
};
