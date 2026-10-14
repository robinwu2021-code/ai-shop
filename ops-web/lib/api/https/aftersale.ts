// 覆盖范围：售后治理（P-6.1）。
import { client } from "../http-client";
import type { AfterSaleApi } from "../contracts/aftersale";

export const afterSaleHttp: AfterSaleApi = {
  // 后端没有 `intervene` 这个参数——「平台介入」就是 status=ARBITRATING，
  // 之前 intervene=1 直接透传，服务端不认识这个参数就当没收到，平台介入 tab 等于没筛
  listAfterSales: (q) => {
    const { intervene, ...rest } = q ?? {};
    return client.get("/ops/after-sales", intervene === "1" ? { ...rest, status: "ARBITRATING" } : rest);
  },
  decideAfterSale: (v) =>
    client.post(`/ops/after-sales/${v.afterSaleNo}/decide`, {
      refund: v.refund, liability: v.liability, verdict: v.verdict,
    }),
  getFastRefundRule: () => client.get("/ops/after-sales/fast-refund-rule"),
  saveFastRefundRule: (v) => client.post("/ops/after-sales/fast-refund-rule", v),
};
