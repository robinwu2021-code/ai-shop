// 评价与回复 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, nextNo, persist, pushMessage } from "@shared/mock/db";
import { REVIEW_RULES } from "@shared/utils/constants";
import type { MerchantApi } from "../contract";

export const reviewMock: Pick<MerchantApi,
  "mReviewList"
  | "mReplyReview"
  | "mAppealReview"
> = {
  // ---------------------------------------------------------------- 评价
  async mReviewList() {
    const merchantNo = db.merchant.merchantNo;
    return delay(db.reviews.filter((r) => r.merchantNo === merchantNo));
  },

  async mReplyReview(reviewNo, reply) {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) throw new Error("评价不存在");
    r.reply = reply;
    persist();
    return delay({ ...r });
  },

  async mAppealReview(reviewNo, reason, images = []) {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) throw new Error("评价不存在");
    // 只有低分可申诉：四星五星开放申诉，等于「凡是不满意的都申诉一遍」，
    // 平台裁决台会被淹掉，真正的恶意差评反而排不上
    if (r.rating > REVIEW_RULES.appealMaxRating) {
      throw new Error(`只有 ${REVIEW_RULES.appealMaxRating} 星及以下的评价可以申诉`);
    }
    if (r.appeal) throw new Error("该评价已申诉过，等待平台裁决");
    if (!reason.trim()) throw new Error("请填写申诉理由");

    r.appeal = {
      appealNo: nextNo("RA"),
      reason: reason.trim(),
      images,
      status: "PENDING",
      submittedAt: Date.now(),
    };
    pushMessage("SYSTEM", "申诉已提交", "平台会在 3 个工作日内给出裁决", "/pages/reviews/index");
    persist();
    return delay({ ...r });
  },
};
