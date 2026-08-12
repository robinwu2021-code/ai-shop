// 覆盖范围：售后治理（P-6.1）。
import { client } from "../http-client";
import type { AfterSaleApi } from "../contracts/aftersale";

export const afterSaleHttp: AfterSaleApi = {
  listAfterSales: (q) => client.get("/ops/after-sales", q),
  decideAfterSale: (v) =>
    client.post(`/ops/after-sales/${v.afterSaleNo}/decide`, {
      refund: v.refund, liability: v.liability, verdict: v.verdict,
    }),
  getFastRefundRule: () => client.get("/ops/after-sales/fast-refund-rule"),
  saveFastRefundRule: (v) => client.post("/ops/after-sales/fast-refund-rule", v),
};
