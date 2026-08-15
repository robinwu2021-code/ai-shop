// 覆盖范围：商品与类目（P-3）。
import { client } from "../http-client";
import type { ProductApi } from "../contracts/product";
import type { Category, Page, ProductGoods, Sku, SpecTemplate } from "@/lib/types";

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

  getGoodsDetail: (goodsNo) => client.get(`/ops/goods/${goodsNo}`),
  // reason 进请求体，不进 query —— 它是处置的事实依据，会原样进商家 B 端，
  // 放 URL 里既进访问日志又有长度上限
  forceOffGoods: (goodsNo, reason) => client.post(`/ops/goods/${goodsNo}/force-off`, { reason }),

  /*
   * sku 级动作 —— **已接真后端** `/ops/skus/**`（P-3.3，2026-08-13）。
   * 此前这几条是"接了但后端还没有"，真实模式下一律 404。
   *
   * 后端把 audit / force-off 解析到父商品再执行（prd_sku 没有审核态，
   * 审核判的是整件商品），路径保持 sku 粒度是为了不改这一层与页面。
   */
  listSkus: (q) =>
    client.get<BackendSkuPage>("/ops/skus", q).then((p) => ({ ...p, records: p.records.map(toSku) })),
  auditSku: (no, pass, reason) =>
    client.post<BackendSku>(`/ops/skus/${no}/audit`, { pass, reason }).then(toSku),
  forceOffSku: (no, reason) =>
    client.post<BackendSku>(`/ops/skus/${no}/force-off`, { reason }).then(toSku),
  // arriveAt 不传 = 不改（后端语义）——「不改」与「清空」必须分开，
  // 否则只改额度的那次提交会把到货时间抹掉，而「截单必须早于到货」正是靠它成立。
  //
  // ⚠️ 注释必须写在这一行**上面**，不能夹在 `=>` 与 `client.` 之间：
  // gen-openapi 与 openapi-parity 共用同一条正则去认「哪个方法调了 client」，
  // 夹在中间会让它认不出来 —— 后果不是报错，是**生成的 OpenAPI 里整条端点消失**，
  // 而后端照着那份规格写就会漏掉这个接口。
  setSkuPresale: (no, presaleQuota, cutoffAt, arriveAt) =>
    client.post<BackendSku>(`/ops/skus/${no}/presale`, { presaleQuota, cutoffAt, arriveAt }).then(toSku),
  listOversellSkus: () => client.get<BackendSku[]>("/ops/skus/oversell").then((rows) => rows.map(toSku)),

  // ── 规格模板（P-3.4 / E27）
  listSpecTemplates: (q) =>
    client.get<Page<BackendSpecTemplate>>("/ops/spec-templates", q)
      .then((p) => ({ ...p, records: p.records.map(toSpecTemplate) })),
  saveSpecTemplate: (v) => client.post<BackendSpecTemplate>("/ops/spec-templates", v).then(toSpecTemplate),
  archiveSpecTemplate: (no) =>
    client.post<BackendSpecTemplate>(`/ops/spec-templates/${no}/archive`).then(toSpecTemplate),
  unarchiveSpecTemplate: (no) =>
    client.post<BackendSpecTemplate>(`/ops/spec-templates/${no}/unarchive`).then(toSpecTemplate),
};

/** `GET /ops/skus` 的原样返回形状（`OpsSkuDetailVO`，见后端 product/dto）。 */
interface BackendSkuPage {
  records: BackendSku[];
  total: number;
  page: number;
  size: number;
}
interface BackendSku {
  skuNo: string;
  goodsNo: string;
  title: { zh: string | null; en: string | null; ar: string | null };
  merchantNo: string | null;
  merchantName: string | null;
  categoryNo: string | null;
  categoryName: string | null;
  status: string;
  prices: Record<string, number>;
  stock: number;
  presaleQuota: number;
  soldCount: number;
  cutoffAt: string | null;
  arriveAt: string | null;
  createdAt: string | null;
  reason: string | null;
}

/**
 * 后端的 `null` 拍成 `undefined`，`AUDITING` 拍成 `PENDING`。
 *
 * 两处都不是洁癖：`Sku.title.en` 声明的是 `string | undefined`，
 * 直接把 `null` 塞进去会让 `!s.title.en` 与 `"en" in s.title` 两种判空写法结论相反；
 * 状态词表则与 `listGoods` 同一套 —— 两处不一致会让同一件商品在两个 tab 里显示成两种状态。
 */
function toSku(s: BackendSku): Sku {
  return {
    skuNo: s.skuNo,
    title: { zh: s.title?.zh ?? "", en: s.title?.en ?? undefined, ar: s.title?.ar ?? undefined },
    merchantNo: s.merchantNo ?? "",
    merchantName: s.merchantName ?? "",
    categoryNo: s.categoryNo ?? "",
    categoryName: s.categoryName ?? "",
    status: (s.status === "AUDITING" ? "PENDING" : s.status) as Sku["status"],
    prices: s.prices ?? {},
    stock: s.stock ?? 0,
    presaleQuota: s.presaleQuota ?? 0,
    soldCount: s.soldCount ?? 0,
    cutoffAt: s.cutoffAt ?? undefined,
    arriveAt: s.arriveAt ?? undefined,
    createdAt: s.createdAt ?? "",
    reason: s.reason ?? undefined,
  };
}

/** `OpsSpecTemplateVO`。`categoryType` 为空 = 不限品类，与「某个品类」是两回事。 */
interface BackendSpecTemplate {
  templateNo: string;
  scope: string;
  categoryType: string | null;
  name: string;
  options: { code: string; label: string }[];
  archivedAt: string | null;
  createdAt: string | null;
}

function toSpecTemplate(t: BackendSpecTemplate): SpecTemplate {
  return {
    templateNo: t.templateNo,
    scope: t.scope,
    categoryType: (t.categoryType ?? undefined) as SpecTemplate["categoryType"],
    name: t.name,
    options: t.options ?? [],
    archivedAt: t.archivedAt ?? undefined,
    createdAt: t.createdAt ?? undefined,
  };
}
