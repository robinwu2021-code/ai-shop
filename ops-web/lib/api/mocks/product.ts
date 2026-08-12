// 覆盖范围：商品与类目（P-3）。上架前的三条校验是本域的核心。
import * as db from "@/lib/mock/db";
import { MARKETS, MAX_CATEGORY_LEVEL, SKU_TRANSITIONS, type Category, type Sku } from "@/lib/types";
import type { ProductApi } from "../contracts/product";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function findCategory(no: string): Category {
  const c = db.categories.find((x) => x.categoryNo === no);
  if (!c) notFound("类目", "Category", no);
  return c;
}
function findSku(no: string): Sku {
  const s = db.skus.find((x) => x.skuNo === no);
  if (!s) notFound("商品", "Item", no);
  return s;
}

export const productMock: ProductApi = {
  listGoodsAuditQueue: (q = {}) =>
    // 队列只给待审的：已处理的属于历史，混在待办里会让人重复审
    wait(db.paginate(db.goodsAudits, q.page, q.size, (g) => g.status === "PENDING")),

  auditGoods: async (goodsNo, approved, reason) => {
    const g = db.goodsAudits.find((x) => x.goodsNo === goodsNo);
    if (!g) notFound("商品", "Goods", goodsNo);
    // 驳回必须写理由 —— mock 也拦，否则这段校验在开发期永远走不到
    if (!approved && !reason?.trim()) fail("驳回必须写理由", "A rejection must carry a reason");
    // 通过后进的是「在架」而不是一个中间态 —— 与后端同口径
    g.status = approved ? "ON_SALE" : "REJECTED";
    return wait({ ...g });
  },

  listCategories: async (q = {}) =>
    wait(
      db.categories.filter((c) =>
        db.liveHit(c, q.showArchived) &&
        db.eqHit(q.template, c.template) &&
        db.kwHit(q.keyword, c.categoryNo, c.name, c.i18n.en),
      ),
    ),

  saveCategory: async (v) => {
    const parent = v.parentNo ? findCategory(v.parentNo) : undefined;
    const level = parent ? parent.level + 1 : 1;
    // 三级封顶：再深一层，C 端的类目导航就没法展示了（也没有第四层的产品定义）
    if (level > MAX_CATEGORY_LEVEL) fail(`类目最多 ${MAX_CATEGORY_LEVEL} 级，不能在三级类目下再建子类目`, `Categories go ${MAX_CATEGORY_LEVEL} levels deep — a third-level category cannot take children`);
    if (!v.name?.trim()) fail("类目名称必填", "A category name is required");
    const saved = db.upsert<Category>(
      db.categories,
      {
        ...v, level, skuCount: 0,
        i18n: { zh: v.name, ...(v.i18nEn ? { en: v.i18nEn } : {}) },
      },
      "categoryNo",
      () => db.nextNo("CAT", db.categories, 900, "categoryNo"),
    );
    return wait(saved, 400);
  },

  archiveCategory: async (categoryNo) => {
    const c = findCategory(categoryNo);
    // 断枝检查：归档一个还挂着子类目/在售商品的类目，C 端类目树会出现走不通的分支
    const hasChild = db.categories.some((x) => x.parentNo === categoryNo && !x.archivedAt);
    if (hasChild) fail("该类目下还有子类目，请先处理子类目", "This category still has children — deal with those first");
    const onSale = db.skus.filter((s) => s.categoryNo === categoryNo && s.status === "ON_SALE").length;
    if (onSale > 0) fail(`该类目下还有 ${onSale} 个在售商品，请先下架`, `${onSale} items are still on sale in this category — take them down first`);
    return wait(db.archiveRow(db.categories, "categoryNo", categoryNo), 400);
  },

  unarchiveCategory: async (no) => wait(db.unarchiveRow(db.categories, "categoryNo", no), 400),

  listSkus: (q = {}) =>
    wait(
      db.paginate(db.skus, q.page, q.size, (s) =>
        db.eqHit(q.merchantNo, s.merchantNo) &&
        db.eqHit(q.status, s.status) &&
        db.eqHit(q.categoryNo, s.categoryNo) &&
        db.kwHit(q.keyword, s.skuNo, s.title.zh, s.title.en, s.merchantName, s.categoryName),
      ),
    ),

  /**
   * 商品池，goods 粒度。mock 数据里每个 sku 本来就是独立商品（没有一件多规格的样本），
   * 所以按 1:1 包一层——goodsNo 借用 skuNo，字段照抄，`skus` 只放这一条。
   * 真实后端是货真价实的一对多，这里的简化不影响筛选交互本身对不对。
   */
  listGoods: (q = {}) =>
    wait(
      db.paginate(db.skus, q.page, q.size, (s) =>
        db.eqHit(q.merchantNo, s.merchantNo) &&
        db.eqHit(q.status, s.status) &&
        db.eqHit(q.categoryNo, s.categoryNo) &&
        db.kwHit(q.keyword, s.skuNo, s.title.zh, s.title.en, s.merchantName, s.categoryName),
      ),
    ).then((p) => ({
      ...p,
      records: p.records.map((s) => ({
        goodsNo: s.skuNo,
        title: s.title,
        merchantNo: s.merchantNo,
        merchantName: s.merchantName,
        categoryNo: s.categoryNo,
        categoryName: s.categoryName,
        status: s.status,
        skus: [{ skuNo: s.skuNo, optionValues: [], spec: undefined, prices: s.prices, stock: s.stock }],
      })),
    })),

  auditSku: async (skuNo, pass, reason) => {
    const s = findSku(skuNo);
    db.assertTransition(SKU_TRANSITIONS, s.status, pass ? "ON_SALE" : "REJECTED", "商品", "Item");
    if (!pass) {
      if (!reason?.trim()) fail("驳回必须填写原因，商家会原样看到", "Rejection needs a reason — the merchant sees it verbatim");
      s.status = "REJECTED";
      s.reason = reason.trim();
      return wait(s, 400);
    }
    // ① 基准语言文案齐全：en/ar 可缺（按 R9 回落到 zh），zh 缺了就没有任何可展示的标题
    if (!s.title.zh?.trim()) fail("中文标题为空，无法上架", "The Chinese title is empty, so it cannot go on sale");
    // ② B6：多市场必须分别定价。缺一个市场就上架，那个市场的用户会看到没有价格的商品
    const missing = MARKETS.filter((m) => s.prices[m] === undefined);
    if (missing.length) fail(`缺少市场定价：${missing.join(" / ")}（B6 要求各市场分别定价）`, `Missing prices for: ${missing.join(" / ")} — B6 requires a price per market`);
    // ③ 类目资质：商家没有该类目要求的经营类目授权，商品不予通过。
    //    判据是类目上的 requiredCode 与商家档案的 categoryCodes，**不是**拿资质文案做前缀匹配
    //    （见 lib/types/product.ts#requiredCode 的说明）。
    const cat = db.categories.find((c) => c.categoryNo === s.categoryNo);
    if (cat?.requiredCode) {
      const merchant = db.merchants.find((m) => m.merchantNo === s.merchantNo);
      const granted = merchant?.categoryCodes ?? [];
      if (!granted.includes(cat.requiredCode)) {
        fail(`商家未获得「${cat.name}」的经营授权（需 ${cat.requiredCode}：${cat.qualifications.join("、")}）`, `The merchant is not permitted to sell in “${cat.name}” (needs ${cat.requiredCode}: ${cat.qualifications.join(", ")})`);
      }
    }
    s.status = "ON_SALE";
    s.reason = undefined;
    return wait(s, 400);
  },

  forceOffSku: async (skuNo, reason) => {
    const s = findSku(skuNo);
    if (s.status !== "ON_SALE") fail("只有在售商品可以强制下架", "Only items on sale can be forcibly delisted");
    if (!reason?.trim()) fail("强制下架必须填写原因，商家会原样看到", "A forced delisting needs a reason — the merchant sees it verbatim");
    s.status = "OFF_SALE";
    s.reason = reason.trim();
    return wait(s, 400);
  },

  setSkuPresale: async (skuNo, presaleQuota, cutoffAt) => {
    const s = findSku(skuNo);
    if (presaleQuota < 0) fail("预售额度不能为负", "The pre-sale allowance cannot be negative");
    // 截单晚于到货 = 货到了还能下单，必然超卖
    if (s.arriveAt && new Date(cutoffAt) >= new Date(s.arriveAt)) {
      fail("截单时间必须早于到货时间，否则货到了还能继续下单", "The cut-off must come before the arrival time, or people keep ordering after the goods land");
    }
    s.presaleQuota = presaleQuota;
    s.cutoffAt = cutoffAt;
    return wait(s, 400);
  },

  listOversellSkus: async () =>
    // 只报不处置：补货还是退单要人判断，自动关单会把还能补上的团也关掉
    wait(db.skus.filter((s) => s.presaleQuota > 0 && s.soldCount > s.presaleQuota)),
};
