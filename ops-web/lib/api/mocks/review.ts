// 覆盖范围：评价治理（P-13.1）。
import * as db from "@/lib/mock/db";
import { SCORE_WEIGHT_TOTAL } from "@/lib/constants";
import type { ReviewApi } from "../contracts/review";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

export const reviewMock: ReviewApi = {
  listReviews: (q = {}) =>
    wait(
      db.paginate(db.reviews, q.page, q.size, (r) =>
        db.eqHit(q.status, r.status) &&
        db.eqHit(q.merchantNo, r.merchantNo) &&
        // risky=1 只看命中刷评信号的（P-13.1.5）：信号是线索不是结论，所以是筛选项不是自动处置
        (q.risky !== "1" || r.riskFlags.length > 0) &&
        db.kwHit(q.keyword, r.reviewNo, r.merchantName, r.authorNickname, r.content),
      ),
    ),

  decideReview: async (reviewNo, pass, reason) => {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) notFound("评价", "Review", reviewNo);
    if (r.status !== "PENDING") fail("该评价已处理，请刷新列表", "This review has already been handled — refresh the list");
    if (!pass && !reason?.trim()) fail("驳回必须填写原因，用户与商家都会看到", "Rejection needs a reason — both the shopper and the merchant see it");
    r.status = pass ? "PASSED" : "REJECTED";
    r.reason = pass ? undefined : reason?.trim();
    return wait(r, 400);
  },

  listReviewAppeals: (q = {}) =>
    wait(db.paginate(db.reviewAppeals, q.page, q.size, (a) => db.kwHit(q.keyword, a.appealNo, a.reviewNo, a.merchantName, a.reason))),

  decideAppeal: async (appealNo, uphold, verdict) => {
    const a = db.reviewAppeals.find((x) => x.appealNo === appealNo);
    if (!a) notFound("申诉", "Appeal", appealNo);
    if (a.status !== "PENDING") fail("该申诉已裁决，请刷新列表", "This appeal has already been ruled on — refresh the list");
    // 支持与驳回都要写理由：商家会看到，"已读不处理"不是一种结果
    if (!verdict?.trim()) fail("裁决说明必填，商家会原样看到", "A ruling note is required — the merchant sees it verbatim");
    a.status = uphold ? "UPHELD" : "DISMISSED";
    a.verdict = verdict.trim();
    // 支持商家 = 差评下架：申诉裁决要真的作用到评价上，否则页面在骗人
    if (uphold) {
      const r = db.reviews.find((x) => x.reviewNo === a.reviewNo);
      if (r) { r.status = "REJECTED"; r.reason = `申诉成立：${verdict.trim()}`; }
    }
    return wait(a, 400);
  },

  getScoreConfig: async () => wait(db.scoreConfig),

  saveScoreConfig: async (v) => {
    const sum = v.weightProduct + v.weightFulfill + v.weightService;
    if (sum !== SCORE_WEIGHT_TOTAL) fail(`三维权重之和必须为 ${SCORE_WEIGHT_TOTAL}，当前 ${sum}`, `The three weights must add up to ${SCORE_WEIGHT_TOTAL}, currently ${sum}`);
    if (v.newMerchantProtectDays < 0) fail("新商家保护期不能为负", "The new-merchant grace period cannot be negative");
    if (v.decayHalfLifeDays < 1) fail("时效衰减半衰期至少 1 天", "The decay half-life must be at least 1 day");
    Object.assign(db.scoreConfig, v, { updatedAt: "2026-08-06T00:00:00Z", updatedBy: "admin" });
    return wait(db.scoreConfig, 400);
  },
};
