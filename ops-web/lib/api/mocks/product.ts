// 覆盖范围：商品与类目（P-3）。上架前的三条校验是本域的核心。
import * as db from "@/lib/mock/db";
import { MARKETS, MAX_CATEGORY_LEVEL, SKU_TRANSITIONS, type Category, type Sku, type SpecTemplate, type SpuStd, type Topic, type ProductGoods } from "@/lib/types";
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

/**
 * 商品池那份 mock 是 **SKU 粒度**（`db.skus`），而专题接口回的是**商品粒度**。
 * 在这条边界上折一次，别让两种粒度混着流进页面 —— 混了之后
 * 「这一行是一件货还是一个规格」在每个用到的地方都要重新猜一遍。
 */
function asGoods(goodsNo: string): ProductGoods | undefined {
  const s = db.skus.find((x) => x.skuNo === goodsNo);
  if (!s) return undefined;
  return {
    goodsNo: s.skuNo,
    title: s.title,
    merchantNo: s.merchantNo,
    merchantName: s.merchantName,
    categoryNo: s.categoryNo,
    categoryName: s.categoryName,
    status: s.status,
    skus: [],
  };
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
    // 两级封顶（V168）：再深一层，端上的两级选择器就渲染不出来 ——
    // 那种节点查得到、选不到
    if (level > MAX_CATEGORY_LEVEL) fail(`类目最多 ${MAX_CATEGORY_LEVEL} 级，二级之下不能再建子类目`, `Categories go ${MAX_CATEGORY_LEVEL} levels deep — a second-level category cannot take children`);
    if (!v.name?.trim()) fail("类目名称必填", "A category name is required");
    /*
     * 形态**继承父级**，不采信传上来的值：二级与它的一级形态不同，会让
     * 「食品生鲜 → 粮油调味」建出来的商品要求填截单时间。真后端同样强制继承。
     */
    const template = parent ? parent.template : v.template;
    /*
     * 门槛码必须是**启用中**的码。挂一个停用码 = 那个类目永远拒绝所有人：
     * 授权页只从启用码里挑，运营根本授不出去，而商家看到的是「你还没有资质授权」。
     * 2026-08-21 线上真踩到过（熟食卤味 → FOOD、医药健康 → DRUG_RETAIL）。
     */
    if (v.requiredCode) {
      const code = db.authCodeAdmins.find((a) => a.code === v.requiredCode);
      if (!code) notFound("授权码", "Permission code", v.requiredCode);
      if (!code.enabled) {
        fail(`授权码「${code.name}」已停用，挂上去这个类目谁也上不了架`,
          `Permission code “${code.name}” is disabled — nobody could list in this category`);
      }
    }
    const saved = db.upsert<Category>(
      db.categories,
      {
        // sort 不传就沿用原值；新建给 0 —— 不这么写，改个名字会把顺序清成 0
        ...v, level, template, skuCount: 0,
        i18n: { zh: v.name, ...(v.i18nEn ? { en: v.i18nEn } : {}) },
      },
      "categoryNo",
      () => db.nextNo("CAT", db.categories, 900, "categoryNo"),
    );
    return wait(saved, 400);
  },

  // ── 标准品库（TDD-标准品库）──
  // ── 主题分类（陈列，批 E）────────────────────────────────────
  listTopics: async (q = {}) =>
    wait(
      db.topics
        .filter((t) => (q.includeArchived ?? true) || t.status !== "ARCHIVED")
        .map((t) => ({ ...t, goodsCount: (db.topicGoods[t.topicNo] ?? []).length }))
        .sort((a, b) => a.sort - b.sort),
    ),

  saveTopic: async (v) => {
    if (!v.title?.trim()) fail("专题名必填", "A topic needs a title");
    /*
     * 结束早于开始**直接拒**：不拦的话那个专题从建出来的第一秒就不生效，
     * 而运营端列表里它看着完全正常，只有 C 端什么都不显示。
     */
    if (v.startAt && v.endAt && v.endAt < v.startAt) {
      fail("结束时间早于开始时间", "The end time is before the start time");
    }
    const existing = v.topicNo ? db.topics.find((t) => t.topicNo === v.topicNo) : undefined;
    if (v.topicNo && !existing) notFound("专题", "Topic", v.topicNo);
    const row: Topic = existing ?? {
      topicNo: `TP${String(db.topics.length + 1).padStart(4, "0")}`,
      title: "", sort: 0, status: "ACTIVE", goodsCount: 0,
    };
    row.title = v.title.trim();
    row.subtitle = v.subtitle?.trim() || undefined;
    row.cover = v.cover;
    if (v.sort !== undefined) row.sort = v.sort;
    // 显式传 undefined 是「取消档期」，常设专题正是这样从限时改回长期的
    row.startAt = v.startAt;
    row.endAt = v.endAt;
    if (!existing) {
      db.topics.push(row);
      db.topicGoods[row.topicNo] = [];
    }
    return wait({ ...row, goodsCount: (db.topicGoods[row.topicNo] ?? []).length });
  },

  setTopicArchived: async (topicNo, archived) => {
    const t = db.topics.find((x) => x.topicNo === topicNo);
    if (!t) notFound("专题", "Topic", topicNo);
    // 归档不删：分享出去的海报与历史链接都还指着它
    t.status = archived ? "ARCHIVED" : "ACTIVE";
    return wait({ ...t, goodsCount: (db.topicGoods[topicNo] ?? []).length });
  },

  listTopicGoods: async (topicNo, q = {}) => {
    const nos = db.topicGoods[topicNo] ?? [];
    return wait(db.paginate(nos.map(asGoods).filter((x) => !!x), q.page, q.size));
  },

  setTopicGoods: async (topicNo, goodsNos) => {
    if (!db.topics.find((x) => x.topicNo === topicNo)) notFound("专题", "Topic", topicNo);
    /*
     * **只收在架商品**：摆一件下架/待审的货进去，C 端点进去是空位，
     * 而运营在后台看到它明明在列表里 —— 两个页面对同一件货给出相反的答案。
     */
    for (const no of goodsNos) {
      const g = db.skus.find((s) => s.skuNo === no);
      if (!g) notFound("商品", "Product", no);
      if (g.status !== "ON_SALE") {
        fail(`「${g.title.zh}」不在售，不能摆进专题`, `“${g.title.en}” is not on sale`);
      }
    }
    db.topicGoods[topicNo] = [...goodsNos];
    return wait(db.paginate(goodsNos.map(asGoods).filter((x) => !!x), 1, 100));
  },

  listSpuStd: (q = {}) =>
    wait(
      db.paginate(
        // 被引用得多的排前面：那是「别的店都在用这一条」，对录入的人是有效信号
        [...db.spuStds].sort((a, b) => (b.refCount ?? 0) - (a.refCount ?? 0)),
        q.page, q.size,
        (t) =>
          db.liveHit(t, q.showArchived) &&
          db.eqHit(q.categoryNo, t.categoryNo) &&
          // 标题与别名一起搜：商家嘴里的「洋芋」与标题「土豆」对不上时，
          // 结果不是报错，是他以为标准库里没有 —— 然后自建一个
          db.kwHit(q.keyword, t.stdNo, t.title, t.keywords ?? ""),
      ),
    ),

  saveSpuStd: async (v) => {
    if (!v.title?.trim()) fail("标准品名称必填", "A title is required");
    if (!v.categoryNo) fail("类目必填 —— 商品形态由它派生", "A category is required — the product type derives from it");
    /*
     * **每个规格选项必须带 code**。前端也拦一道而不是只靠后端：
     * 录一条标准品要填好几组规格，走到服务端才被拒等于让运营重填一遍。
     */
    for (const g of v.specGroups ?? []) {
      const codes = g.optionCodes ?? [];
      if (!g.options?.length || codes.length !== g.options.length || codes.some((x) => !x?.trim())) {
        fail(`规格「${g.name || "?"}」的每个选项都要填编码 —— 没有编码的标准品与手输没有区别`,
          `Every option in "${g.name || "?"}" needs a code`);
      }
      if (new Set(codes).size !== codes.length) {
        fail(`规格「${g.name}」里有重复的编码`, `Duplicate codes in "${g.name}"`);
      }
    }
    const saved = db.upsert<SpuStd>(
      db.spuStds,
      { refCount: 0, status: "ACTIVE", ...v } as SpuStd,
      "stdNo",
      () => db.nextNo("STD", db.spuStds, 9000, "stdNo"),
    );
    return wait(saved, 400);
  },

  /*
   * 归档**不检查有没有商品在引用**，与类目归档相反 —— `stdNo` 是溯源不是外键：
   * 归档只是「以后别再从这条建品」，已经建出来的商品照常在售。
   * 拦住反而会让一条录错的标准品因为被引用过就永远撤不下来。
   */
  archiveSpuStd: async (no) => wait(db.archiveRow(db.spuStds, "stdNo", no), 400),
  unarchiveSpuStd: async (no) => wait(db.unarchiveRow(db.spuStds, "stdNo", no), 400),

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
        // presaleOnly 与真后端同样做在"查询"里而不是让页面自己过滤：
        // 页面过滤在 mock 上永远对（样本只有八条），到真库就会因为分页而漏掉
        (!q.presaleOnly || s.presaleQuota > 0) &&
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

  /*
   * mock 里 goods 与 sku 是 1:1（见上面 listGoods 的说明），所以 goodsNo 就是 skuNo。
   * 真实后端是货真价实的一对多，这里的简化不影响抽屉交互本身对不对。
   */
  getGoodsDetail: async (goodsNo) => {
    const s = findSku(goodsNo);
    return wait({
      goodsNo,
      title: s.title.zh,
      cover: undefined,
      // 后端必发这四个数组（可能是空的）。声明成可选会让页面到处写 `?? []`，
      // 而那正是「后端某天真的不发了」也发现不了的写法
      images: [],
      type: "NORMAL",
      categoryNo: s.categoryNo,
      merchant: { merchantNo: s.merchantNo, name: s.merchantName },
      titleI18n: { zh: s.title.zh, ...(s.title.en ? { en: s.title.en } : {}), ...(s.title.ar ? { ar: s.title.ar } : {}) },
      specGroups: [],
      // 详情里的价是**单一价**（后端 SkuVO 就一份），取 CN 那档；多市场价在列表行上
      skus: [{ skuNo: s.skuNo, optionValues: [], spec: undefined, price: s.prices.CN ?? 0, originPrice: null, stock: s.stock }],
      fulfillments: [],
      price: s.prices.CN ?? 0,
      status: s.status,
      auditReason: s.reason ?? null,
    });
  },

  forceOffGoods: async (goodsNo, reason) => {
    const s = findSku(goodsNo);
    // goods 级强制下架 = **撤销过审**，所以只有在架的才谈得上撤
    if (s.status !== "ON_SALE") fail("只有在售商品可以强制下架", "Only goods on sale can be forcibly delisted");
    // 原因原样进商家 B 端：空原因等于让商家猜，猜不到就会反复重提
    if (!reason?.trim()) fail("强制下架必须填写原因，商家会原样看到", "A forced delisting needs a reason — the merchant sees it verbatim");
    /*
     * 落到 REJECTED 而不是 OFF_SALE —— 两者对商家意味着完全不同的下一步：
     * OFF_SALE 他自己点一下就能重新上架，REJECTED 必须改完重新提审。
     * 平台既然是"撤销过审"，就不能给他一条一键复原的路。
     */
    s.status = "REJECTED";
    s.reason = `平台强制下架：${reason.trim()}`;
    // 待审队列里若有同一件商品，状态跟着走：两处不同步的话审核台会显示一个已被撤下的商品
    const g = db.goodsAudits.find((x) => x.goodsNo === goodsNo);
    if (g) g.status = "REJECTED";
    return productMock.getGoodsDetail(goodsNo);
  },

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

  setSkuPresale: async (skuNo, presaleQuota, cutoffAt, arriveAt) => {
    const s = findSku(skuNo);
    if (presaleQuota < 0) fail("预售额度不能为负", "The pre-sale allowance cannot be negative");
    // arriveAt 不传 = 不改（与后端同一条语义）。「不改」与「清空」分开：
    // 只改额度的那次提交若顺手清了到货时间，下面这条校验从此形同虚设
    const arrive = arriveAt || s.arriveAt;
    // 截单晚于到货 = 货到了还能下单，必然超卖
    if (cutoffAt && arrive && new Date(cutoffAt) >= new Date(arrive)) {
      fail("截单时间必须早于到货时间，否则货到了还能继续下单", "The cut-off must come before the arrival time, or people keep ordering after the goods land");
    }
    /*
     * **刻意不拦「额度小于已售」** —— 与后端同一条取舍：拦住看着更严谨，
     * 实际是把问题藏起来（运营改不动额度只好不改，那批已超出去的订单谁也不知道）。
     * 调完这条 SKU 立刻出现在 listOversellSkus 里，有人认领才是重点。
     */
    s.presaleQuota = presaleQuota;
    s.cutoffAt = cutoffAt || undefined;
    if (arriveAt) s.arriveAt = arriveAt;
    return wait(s, 400);
  },

  listOversellSkus: async () =>
    // 只报不处置：补货还是退单要人判断，自动关单会把还能补上的团也关掉
    wait(db.skus.filter((s) => s.presaleQuota > 0 && s.soldCount > s.presaleQuota)),

  // ── 规格模板（P-3.4 / E27）

  listSpecTemplates: (q = {}) =>
    wait(
      db.paginate(db.specTemplates, q.page, q.size, (t) =>
        db.liveHit(t, q.showArchived) &&
        db.eqHit(q.categoryType, t.categoryType ?? undefined) &&
        db.kwHit(q.keyword, t.templateNo, t.name, ...t.options.map((o) => o.code)),
      ),
    ),

  saveSpecTemplate: async (v) => {
    if (!v.name?.trim()) fail("模板名称必填", "A template name is required");
    if (!v.options?.length) fail("至少要有一个选项", "A template needs at least one option");
    /*
     * 每个选项必须带 code —— 这是平台模板存在的唯一理由（B-4.5）。
     * mock 也拦：不拦的话这条规则在开发期永远走不到，
     * 而它正是「平台模板」与「商家手输」的全部区别。
     */
    const codes = new Set<string>();
    for (const o of v.options) {
      if (!o.code?.trim() || !o.label?.trim()) {
        fail("每个选项都要填规格编码和名称，否则各店的写法聚合不到一起",
          "Every option needs both an option code and a label, otherwise the wording each shop uses cannot be grouped");
      }
      // 组内重复会让「500g」和「1kg」在聚合时并成同一个规格 —— 正是 code 要防的事
      if (codes.has(o.code.trim())) fail(`选项编码重复：${o.code}`, `Duplicate option code: ${o.code}`);
      codes.add(o.code.trim());
    }
    const dup = db.specTemplates.find((t) =>
      t.templateNo !== v.templateNo && !t.archivedAt &&
      t.name.trim() === v.name.trim() && (t.categoryType ?? "") === (v.categoryType ?? ""));
    // 同品类重名：商家的下拉里会出现两个「重量」，选哪个都对不上
    if (dup) fail("同一品类下已有同名模板", "A template with this name already exists for this category type");

    const saved = db.upsert<SpecTemplate>(
      db.specTemplates,
      {
        templateNo: v.templateNo,
        // scope 由后端写死，mock 跟着写死 —— 否则 mock 上能造出平台端改不了的商家模板
        scope: "PLATFORM",
        categoryType: (v.categoryType || undefined) as SpecTemplate["categoryType"],
        name: v.name.trim(),
        options: v.options.map((o) => ({ code: o.code.trim(), label: o.label.trim() })),
      },
      "templateNo",
      () => db.nextNo("SPT", db.specTemplates, 900, "templateNo"),
    );
    return wait(saved, 400);
  },

  archiveSpecTemplate: async (no) => wait(db.archiveRow(db.specTemplates, "templateNo", no), 400),
  unarchiveSpecTemplate: async (no) => wait(db.unarchiveRow(db.specTemplates, "templateNo", no), 400),
};
