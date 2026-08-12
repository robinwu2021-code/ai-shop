// 覆盖范围：商品与类目（P-3）。
import { client } from "../http-client";
import type { ProductApi } from "../contracts/product";
import type { Category } from "@/lib/types";

export const productHttp: ProductApi = {
  listGoodsAuditQueue: (q) => client.get("/ops/goods/audit-queue", q),
  auditGoods: (goodsNo, approved, reason) =>
    client.post(`/ops/goods/${goodsNo}/audit`, { approved, reason }),

  /**
   * 后端 `/ops/categories` 早改成了 `{records,total,page,size}`（十个列表端点统一收口那次），
   * 这里没跟着改——本地看不出来，`app/products/page.tsx` 拿 `Category[]` 当数组用，
   * 上生产直接白屏（`all.filter is not a function`）。size 给大一点：类目树总量有限，
   * 契约本来就是"一次给全量"，不能被默认的 50 条分页悄悄截断。
   */
  listCategories: (q) => client.get<{ records: Category[] }>("/ops/categories", { ...q, size: q?.size ?? 500 })
    .then((r) => r.records),
  saveCategory: (v) => client.post("/ops/categories", v),
  archiveCategory: (no) => client.post(`/ops/categories/${no}/archive`),
  unarchiveCategory: (no) => client.post(`/ops/categories/${no}/unarchive`),
  listSkus: (q) => client.get("/ops/skus", q),
  auditSku: (no, pass, reason) => client.post(`/ops/skus/${no}/audit`, { pass, reason }),
  forceOffSku: (no, reason) => client.post(`/ops/skus/${no}/force-off`, { reason }),
  setSkuPresale: (no, presaleQuota, cutoffAt) => client.post(`/ops/skus/${no}/presale`, { presaleQuota, cutoffAt }),
  listOversellSkus: () => client.get("/ops/skus/oversell"),
};
