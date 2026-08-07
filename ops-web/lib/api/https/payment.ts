// 覆盖范围：支付管理（P-4.2）。
import { client } from "../http-client";
import type { PaymentApi } from "../contracts/payment";

export const paymentHttp: PaymentApi = {
  listReconDiffs: (q) => client.get("/ops/payments/recon-diffs", q),
  resolveReconDiff: (v) => client.post(`/ops/payments/recon-diffs/${v.diffNo}/resolve`, v),
  ignoreReconDiff: (v) => client.post(`/ops/payments/recon-diffs/${v.diffNo}/ignore`, v),
  getCloseRule: () => client.get("/ops/payments/close-rule"),
  saveCloseRule: (v) => client.put("/ops/payments/close-rule", v),
};
