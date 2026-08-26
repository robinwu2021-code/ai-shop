// 覆盖范围：支付管理（P-4.2）。
import { client } from "../http-client";
import type { ReconAxisReport, ReconCoverage } from "@/lib/types";
import type { PaymentApi } from "../contracts/payment";

export const paymentHttp: PaymentApi = {
  listReconDiffs: (q) => client.get("/ops/payments/recon-diffs", q),
  reconCoverage: () => client.get<ReconCoverage>("/ops/payments/recon-coverage"),
  reconAxes: () => client.get<ReconAxisReport[]>("/ops/payments/recon-axes"),
  resolveReconDiff: (v) => client.post(`/ops/payments/recon-diffs/${v.diffNo}/resolve`, v),
  ignoreReconDiff: (v) => client.post(`/ops/payments/recon-diffs/${v.diffNo}/ignore`, v),
  getCloseRule: () => client.get("/ops/payments/close-rule"),
  saveCloseRule: (v) => client.put("/ops/payments/close-rule", v),
};
