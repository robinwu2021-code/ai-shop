// 覆盖范围：评价治理（P-13.1）。
import { client } from "../http-client";
import type { ReviewApi } from "../contracts/review";

export const reviewHttp: ReviewApi = {
  listReviews: (q) => client.get("/ops/reviews", q),
  decideReview: (no, pass, reason) => client.post(`/ops/reviews/${no}/decide`, { pass, reason }),
  listReviewAppeals: (q) => client.get("/ops/review-appeals", q),
  decideAppeal: (no, uphold, verdict) => client.post(`/ops/review-appeals/${no}/decide`, { uphold, verdict }),
  getScoreConfig: () => client.get("/ops/review-score-config"),
  saveScoreConfig: (v) => client.post("/ops/review-score-config", v),
};
