// 覆盖范围：商品与类目（P-3）。
import { client } from "../http-client";
import type { ProductApi } from "../contracts/product";

export const productHttp: ProductApi = {
  listCategories: (q) => client.get("/ops/categories", q),
  saveCategory: (v) => client.post("/ops/categories", v),
  archiveCategory: (no) => client.post(`/ops/categories/${no}/archive`),
  unarchiveCategory: (no) => client.post(`/ops/categories/${no}/unarchive`),
  listSkus: (q) => client.get("/ops/skus", q),
  auditSku: (no, pass, reason) => client.post(`/ops/skus/${no}/audit`, { pass, reason }),
  forceOffSku: (no, reason) => client.post(`/ops/skus/${no}/force-off`, { reason }),
  setSkuPresale: (no, presaleQuota, cutoffAt) => client.post(`/ops/skus/${no}/presale`, { presaleQuota, cutoffAt }),
  listOversellSkus: () => client.get("/ops/skus/oversell"),
};
