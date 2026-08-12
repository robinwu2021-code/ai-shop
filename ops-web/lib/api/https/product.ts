// 覆盖范围：商品与类目（P-3）。
import { client } from "../http-client";
import type { ProductApi } from "../contracts/product";
import type { Category, ProductGoods } from "@/lib/types";

/** `GET /ops/goods` 的原样返回形状（`OpsGoodsListVO`，见后端 product/dto）。 */
interface BackendGoodsPage {
  records: BackendGoods[];
  total: number;
  page: number;
  size: number;
}
interface BackendGoods {
  goodsNo: string;
  title: { zh: string; en: string | null; ar: string | null };
  cover: string | null;
  merchantNo: string;
  merchantName: string;
  categoryNo: string | null;
  categoryName: string | null;
  status: string;
  skus: {
    skuNo: string;
    optionValues: string[];
    spec: string | null;
    prices: Record<string, number>;
    stock: number;
  }[];
}

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

  listGoods: (q) =>
    client.get<BackendGoodsPage>("/ops/goods", q).then((p) => ({
      records: p.records.map((g): ProductGoods => ({
        goodsNo: g.goodsNo,
        title: { zh: g.title.zh, en: g.title.en ?? undefined, ar: g.title.ar ?? undefined },
        cover: g.cover ?? undefined,
        merchantNo: g.merchantNo,
        merchantName: g.merchantName,
        categoryNo: g.categoryNo ?? undefined,
        categoryName: g.categoryName ?? undefined,
        // 真实后端待审态叫 AUDITING，mock/SkuStatus 词表里叫 PENDING——同一件事，统一成一个词
        status: g.status === "AUDITING" ? "PENDING" : g.status,
        skus: g.skus.map((s) => ({
          skuNo: s.skuNo,
          optionValues: s.optionValues,
          spec: s.spec ?? undefined,
          prices: s.prices,
          stock: s.stock,
        })),
      })),
      total: p.total,
      page: p.page,
      size: p.size,
    })),

  /*
   * 下面这几个 sku 级动作真实后端目前没有对应接口（只有 goods 粒度的 auditGoods）——
   * 保留调用 /ops/skus/** 是诚实地"接了但后端还没有"，不是漏改；
   * mock 模式下这几个是唯一能验证 B6/资质/预售规则的地方，见 contract 里的说明。
   */
  listSkus: (q) => client.get("/ops/skus", q),
  auditSku: (no, pass, reason) => client.post(`/ops/skus/${no}/audit`, { pass, reason }),
  forceOffSku: (no, reason) => client.post(`/ops/skus/${no}/force-off`, { reason }),
  setSkuPresale: (no, presaleQuota, cutoffAt) => client.post(`/ops/skus/${no}/presale`, { presaleQuota, cutoffAt }),
  listOversellSkus: () => client.get("/ops/skus/oversell"),
};
