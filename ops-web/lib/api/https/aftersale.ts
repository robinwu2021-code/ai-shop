// 覆盖范围：售后治理（P-6.1）。
import { client } from "../http-client";
import type { AfterSaleApi } from "../contracts/aftersale";

export const afterSaleHttp: AfterSaleApi = {
  listAfterSales: (q) => client.get("/ops/after-sales", q),
  setAfterSaleStatus: (no, status) => client.post(`/ops/after-sales/${no}/status`, { status }),
  decideAfterSale: (v) => client.post(`/ops/after-sales/${v.asNo}/decide`, v),
  getFastRefundRule: () => client.get("/ops/after-sales/fast-refund-rule"),
  saveFastRefundRule: (v) => client.post("/ops/after-sales/fast-refund-rule", v),
};
