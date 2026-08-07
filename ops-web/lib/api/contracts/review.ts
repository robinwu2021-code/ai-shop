// 覆盖范围：评价治理（P-13.1）。
import type { Page, Review, ReviewAppeal, ScoreConfig } from "@/lib/types";
import type { PageQ, ReviewQ } from "../query";

export interface ReviewApi {
  listReviews(q?: ReviewQ): Promise<Page<Review>>;
  /** 审核裁决（P-13.1.1/13.1.2）。驳回必须带原因 —— 与门店审核同一条规矩。 */
  decideReview(reviewNo: string, pass: boolean, reason?: string): Promise<Review>;

  listReviewAppeals(q?: PageQ): Promise<Page<ReviewAppeal>>;
  /**
   * 申诉裁决（P-13.1.3）。uphold=true 支持商家（差评下架），false 驳回申诉。
   * 两种结论都必须写裁决说明：商家会看到，"已读不处理"不是一种结果。
   */
  decideAppeal(appealNo: string, uphold: boolean, verdict: string): Promise<ReviewAppeal>;

  getScoreConfig(): Promise<ScoreConfig>;
  /** 评分算法参数（P-13.1.4）。三维权重之和必须为 100。 */
  saveScoreConfig(v: Pick<ScoreConfig, "weightProduct" | "weightFulfill" | "weightService" | "newMerchantProtectDays" | "decayHalfLifeDays">): Promise<ScoreConfig>;
}
