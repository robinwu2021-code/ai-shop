// 覆盖范围：商品与类目（P-3）。上架前的三条校验是本域的核心。
import * as db from "@/lib/mock/db";
import { MARKETS, MAX_CATEGORY_LEVEL, SKU_TRANSITIONS, type Category, type CategorySpecDim, type SpecDim, type SpecValue, type Sku, type SpecTemplate, type SpuStd, type Topic, type ProductGoods } from "@/lib/types";
import type { ProductApi } from "../contracts/product";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

/**
 * mock 的类目规格绑定：只覆盖六个类目，其余留空。
 *
 * 真库里是 116 条绑定（V196 种子），这里不抄一份 —— mock 的用途是让界面的
 * 两种状态都走得到：配好的（含主维度、通用/专用、PROP、归一量）与空着的（标红计数）。
 */
const MOCK_CATEGORY_SPECS: Record<string, CategorySpecDim[]> = {
  CAT110: [
    { dimNo: "SD_WEIGHT", code: "WEIGHT", name: "重量", valueType: "QUANT", unit: "g",
      usage: "SALE", universal: true, primary: true, valueCount: 4, values: [
        { valueNo: "SV_WEIGHT_W250G", code: "W250G", label: "约半斤", numericValue: 250, numericUnit: "g" },
        { valueNo: "SV_WEIGHT_W500G", code: "W500G", label: "约1斤", numericValue: 500, numericUnit: "g" },
        { valueNo: "SV_WEIGHT_W1KG", code: "W1KG", label: "约2斤", numericValue: 1000, numericUnit: "g" },
        { valueNo: "SV_WEIGHT_W1500G", code: "W1500G", label: "约3斤", numericValue: 1500, numericUnit: "g" },
      ] },
    { dimNo: "SD_PACK", code: "PACK", name: "包装", valueType: "ENUM", usage: "SALE",
      universal: true, primary: false, valueCount: 3, values: [
        { valueNo: "SV_PACK_PBULK", code: "PBULK", label: "散装" },
        { valueNo: "SV_PACK_PBAG", code: "PBAG", label: "袋装" },
        { valueNo: "SV_PACK_PBOX", code: "PBOX", label: "盒装" },
      ] },
    { dimNo: "SD_ORIGIN", code: "ORIGIN", name: "产地", valueType: "ENUM", usage: "PROP",
      universal: true, primary: false, valueCount: 3, values: [
        { valueNo: "SV_ORIGIN_ORGLOCAL", code: "ORGLOCAL", label: "本地" },
        { valueNo: "SV_ORIGIN_ORGCN", code: "ORGCN", label: "国产" },
        { valueNo: "SV_ORIGIN_ORGIMP", code: "ORGIMP", label: "进口" },
      ] },
  ],
  CAT170: [
    { dimNo: "SD_WEIGHT", code: "WEIGHT", name: "重量", valueType: "QUANT", unit: "g",
      usage: "SALE", universal: true, primary: true, valueCount: 3, values: [
        { valueNo: "SV_WEIGHT_W500G", code: "W500G", label: "1斤", numericValue: 500, numericUnit: "g" },
        { valueNo: "SV_WEIGHT_W1KG", code: "W1KG", label: "2斤", numericValue: 1000, numericUnit: "g" },
        { valueNo: "SV_WEIGHT_W2KG", code: "W2KG", label: "4斤", numericValue: 2000, numericUnit: "g" },
      ] },
    { dimNo: "SD_CUT", code: "CUT", name: "处理方式", valueType: "ENUM", usage: "SALE",
      universal: false, primary: false, valueCount: 4, values: [
        { valueNo: "SV_CUT_CUTWHOLE", code: "CUTWHOLE", label: "整只" },
        { valueNo: "SV_CUT_CUTCHUNK", code: "CUTCHUNK", label: "切块" },
        { valueNo: "SV_CUT_CUTSLICE", code: "CUTSLICE", label: "切片" },
        { valueNo: "SV_CUT_CUTMINCE", code: "CUTMINCE", label: "绞馅" },
      ] },
  ],
  CAT290: [
    { dimNo: "SD_DIAMETER", code: "DIAMETER", name: "口径", valueType: "QUANT", unit: "cm",
      usage: "SALE", universal: true, primary: true, valueCount: 4, values: [
        { valueNo: "SV_DIAMETER_DM16", code: "DM16", label: "16cm", numericValue: 16, numericUnit: "cm" },
        { valueNo: "SV_DIAMETER_DM20", code: "DM20", label: "20cm", numericValue: 20, numericUnit: "cm" },
        { valueNo: "SV_DIAMETER_DM24", code: "DM24", label: "24cm", numericValue: 24, numericUnit: "cm" },
        { valueNo: "SV_DIAMETER_DM28", code: "DM28", label: "28cm", numericValue: 28, numericUnit: "cm" },
      ] },
    // 锅具的材质是分价的那一维，所以这一类目把它覆盖成 SALE
    { dimNo: "SD_MATERIAL", code: "MATERIAL", name: "材质", valueType: "ENUM", usage: "SALE",
      universal: true, primary: false, valueCount: 3, values: [
        { valueNo: "SV_MATERIAL_MATSTEEL", code: "MATSTEEL", label: "不锈钢" },
        { valueNo: "SV_MATERIAL_MATIRON", code: "MATIRON", label: "铸铁" },
        { valueNo: "SV_MATERIAL_MATCERAMIC", code: "MATCERAMIC", label: "陶瓷" },
      ] },
  ],
  CAT610: [
    { dimNo: "SD_COLOR", code: "COLOR", name: "颜色", valueType: "ENUM", usage: "SALE",
      universal: true, primary: true, valueCount: 3, values: [
        { valueNo: "SV_COLOR_CLRBLACK", code: "CLRBLACK", label: "黑色" },
        { valueNo: "SV_COLOR_CLRWHITE", code: "CLRWHITE", label: "白色" },
        { valueNo: "SV_COLOR_CLRSILVER", code: "CLRSILVER", label: "银色" },
      ] },
    { dimNo: "SD_STORAGE", code: "STORAGE", name: "存储", valueType: "ENUM", usage: "SALE",
      universal: false, primary: false, valueCount: 3, values: [
        { valueNo: "SV_STORAGE_S128G", code: "S128G", label: "128G", numericValue: 128 },
        { valueNo: "SV_STORAGE_S256G", code: "S256G", label: "256G", numericValue: 256 },
        { valueNo: "SV_STORAGE_S512G", code: "S512G", label: "512G", numericValue: 512 },
      ] },
  ],
  CAT310: [
    { dimNo: "SD_ROOM", code: "ROOM", name: "房型", valueType: "ENUM", usage: "SALE",
      universal: false, primary: true, valueCount: 4, values: [
        { valueNo: "SV_ROOM_R1", code: "R1", label: "一居", numericValue: 1 },
        { valueNo: "SV_ROOM_R2", code: "R2", label: "两居", numericValue: 2 },
        { valueNo: "SV_ROOM_R3", code: "R3", label: "三居", numericValue: 3 },
        { valueNo: "SV_ROOM_R4", code: "R4", label: "四居及以上", numericValue: 4 },
      ] },
  ],
  CAT720: [
    { dimNo: "SD_WEIGHT", code: "WEIGHT", name: "重量", valueType: "QUANT", unit: "g",
      usage: "SALE", universal: true, primary: true, valueCount: 3, values: [
        { valueNo: "SV_WEIGHT_W100G", code: "W100G", label: "100g", numericValue: 100, numericUnit: "g" },
        { valueNo: "SV_WEIGHT_W250G", code: "W250G", label: "250g", numericValue: 250, numericUnit: "g" },
        { valueNo: "SV_WEIGHT_W500G", code: "W500G", label: "500g", numericValue: 500, numericUnit: "g" },
      ] },
    { dimNo: "SD_FLAVOR", code: "FLAVOR", name: "口味", valueType: "ENUM", usage: "SALE",
      universal: true, primary: false, valueCount: 3, values: [
        { valueNo: "SV_FLAVOR_FLVPLAIN", code: "FLVPLAIN", label: "原味" },
        { valueNo: "SV_FLAVOR_FLVSPICY", code: "FLVSPICY", label: "香辣" },
        { valueNo: "SV_FLAVOR_FLVSWEET", code: "FLVSWEET", label: "甜味" },
      ] },
  ],
};

/** mock 的规格库：从上面那份类目绑定里反推出来，够把两个页面的交互走通 */
const MOCK_SPEC_DIMS: SpecDim[] = [];
function seedMockDims() {
  if (MOCK_SPEC_DIMS.length) return;
  const seen = new Map<string, SpecDim>();
  for (const dims of Object.values(MOCK_CATEGORY_SPECS)) {
    for (const d of dims) {
      const row = seen.get(d.dimNo) ?? {
        dimNo: d.dimNo, code: d.code, name: d.name, valueType: d.valueType,
        unit: d.unit ?? null, usageType: d.usage, universal: d.universal,
        scope: "PLATFORM", sort: 100, status: "ACTIVE",
        valueCount: 0, inUse: 0, values: [],
      };
      for (const v of d.values) {
        if (!row.values.some((x) => x.valueNo === v.valueNo)) {
          row.values.push({
            valueNo: v.valueNo, dimNo: d.dimNo, code: v.code, label: v.label,
            numericValue: v.numericValue ?? null, numericUnit: v.numericUnit ?? null,
            aliases: [], scope: "PLATFORM", sort: row.values.length * 10 + 10,
            status: "ACTIVE", merchantCount: 0,
          });
        }
      }
      row.valueCount = row.values.length;
      row.inUse += 1;
      seen.set(d.dimNo, row);
    }
  }
  // 一条商家自建值：没有它，「提升为平台值」那半边界面开发期永远走不到
  const weight = seen.get("SD_WEIGHT");
  if (weight) {
    weight.values.push({
      valueNo: "SV_WEIGHT_M750", dimNo: "SD_WEIGHT", code: "M750", label: "750g",
      numericValue: 750, numericUnit: "g", aliases: ["1.5斤"], scope: "MERCHANT",
      entityNo: "M0001", sort: 900, status: "ACTIVE", merchantCount: 3,
    });
    weight.valueCount = weight.values.length;
  }
  MOCK_SPEC_DIMS.push(...seen.values());
}
const allMockValues = () => MOCK_SPEC_DIMS.flatMap((d) => d.values);
function setValueStatus(valueNo: string, status: string): SpecValue {
  const v = allMockValues().find((x) => x.valueNo === valueNo)!;
  v.status = status;
  return v;
}

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

/**
 * 二级类目（未归档）。类目 × 支付方式 / 积分 / 规格三张表**共用同一份骨架** ——
 * 三页并排放着，行不一样多的话，运营会以为哪一页漏了类目。
 */
function leafCategories() {
  return db.categories.filter((cat) => cat.level === 2 && !cat.archivedAt);
}
function parentNameOf(cat: Category) {
  return db.categories.find((p) => p.categoryNo === cat.parentNo)?.name ?? "";
}
/** 保存后的覆盖值。mock 不落库，但改完要看得见变化，否则以为按钮没生效 */
const mockPayModes = new Map<string, boolean>();
const mockPoints = new Map<string, { earnMode: "FIXED" | "RATIO" | null; earnValue: number | null }>();

export const productMock: ProductApi = {
  listGoodsAuditQueue: (q = {}) =>
    // 队列只给待审的：已处理的属于历史，混在待办里会让人重复审
    wait(db.paginate(db.goodsAudits, q.page, q.size, (g) => g.status === "PENDING")),

  resyncCommunityPool: async (entityNo) => {
    /*
     * 返回**真的重建了几件** —— 按主体过滤时只数那家的商品，不传就数全部。
     *
     * <p>mock 这里恒返 0 的话，界面上「重建了 N 件」永远是 0，而 0 恰好也是
     * 「跑了但没有需要改的」的合法结果 —— 于是开发时根本看不出这个数有没有接对。
     */
    const n = db.skus.filter((x) => !entityNo || x.merchantNo === entityNo).length;
    return wait(n, 600);
  },

  auditGoods: async (goodsNo, approved, reason) => {
    const g = db.goodsAudits.find((x) => x.goodsNo === goodsNo);
    if (!g) notFound("商品", "Goods", goodsNo);
    // 驳回必须写理由 —— mock 也拦，否则这段校验在开发期永远走不到
    if (!approved && !reason?.trim()) fail("驳回必须写理由", "A rejection must carry a reason");
    // 通过后进的是「在架」而不是一个中间态 —— 与后端同口径
    g.status = approved ? "ON_SALE" : "REJECTED";
    return wait({ ...g });
  },

  /*
   * 草稿差异（双版本）。mock 只演两种形态：**队列第一件**给一份演示 diff
   * （审核抽屉的「本次过审将生效的变更」在开发期要看得见长什么样），
   * 其余返回 null（老链路的内容审核 —— 抽屉不渲染这一段，这条路也要能看见）。
   * 真 diff 由服务端 dry-run 烘焙算，后端场景测试守着。
   */
  goodsDraftPreview: async (goodsNo) => {
    // 队列只列 PENDING（见 listGoodsAuditQueue），演示 diff 挂在**队首那件**上
    const first = db.goodsAudits.find((x) => x.status === "PENDING");
    if (!first || first.goodsNo !== goodsNo) return wait(null);
    return wait({
      changes: [
        { field: "title", label: "标题", before: first.title, after: `${first.title}（新版）` },
        { field: "price", label: "价格", before: "¥29.90", after: "¥27.90" },
        { field: "spec", label: "规格", before: "容量（3kg / 5kg）", after: "容量（3kg / 5kg / 10kg）" },
      ],
      blocked: [],
      stale: false,
      baseVersion: 1,
    });
  },

  // 与后端 PageData 同形。类目树整棵要给（页面自己拼层级），所以 size 放大而不是真分页
  listCategories: async (q = {}) =>
    wait(
      db.paginate(db.categories, undefined, 500, (c) =>
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
          db.eqHit(q.source, t.source) &&
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

  /*
   * 批量改状态。**返回真正改动了的条数**，不是传进来的条数 ——
   * 已经是目标状态的不计，运营才看得出「点下去到底生效了几条」。
   * 与真实服务端同一条口径，否则 mock 上永远看不到「勾了 20 条只变了 3 条」这种情形。
   */
  bulkSpuStdStatus: async (stdNos, status) => {
    let changed = 0;
    for (const no of stdNos ?? []) {
      const row = db.spuStds.find((t) => t.stdNo === no);
      if (!row) continue;
      const archived = !!row.archivedAt;
      if (status === "ARCHIVED" && !archived) { db.archiveRow(db.spuStds, "stdNo", no); changed++; }
      if (status === "ACTIVE" && archived) { db.unarchiveRow(db.spuStds, "stdNo", no); changed++; }
    }
    return wait({ changed }, 500);
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

  /*
   * 类目 × 规格（规格库 V195）。mock 只绑六个类目 —— **刻意留出缺口**：
   * 这张表的第一职责就是把「还没配规格的类目」顶到眼前，样本里全配满的话，
   * 那半边界面（标红的缺口、缺口计数）在开发期永远走不到。
   */
  categoryArchiveImpact: (no) => {
    const goods = db.skus.filter((s) => s.categoryNo === no);
    return wait({
      goodsCount: goods.length,
      onSaleCount: goods.filter((s) => s.status === "ON_SALE").length,
      activeChildren: db.categories.filter((x) => x.parentNo === no && !x.archivedAt).length,
    });
  },

  // ── 规格库（V195）。mock 里只有一份内存副本，够把两个页面的交互走通
  listSpecDims: (q = {}) => {
    seedMockDims();
    return wait(
      MOCK_SPEC_DIMS
        .filter((d) => q.universal === undefined || d.universal === q.universal)
        .filter((d) => q.showArchived || d.status === "ACTIVE")
        .filter((d) => !q.keyword || d.name.includes(q.keyword) || d.code.includes(q.keyword)),
    );
  },
  saveSpecDim: async (v) => {
    const name = (v.name ?? "").trim();
    // 与后端同一条规范：万能词不能当维度名 —— 它什么都不说
    if (["规格", "型号", "类型", "属性", "参数"].includes(name)) {
      fail("「" + name + "」太泛，换一个说清楚是什么的名字", "Too generic a dimension name");
    }
    const found = MOCK_SPEC_DIMS.find((d) => d.dimNo === v.dimNo);
    if (found) {
      Object.assign(found, v, { name });
      return wait(found);
    }
    const row: SpecDim = {
      dimNo: "SD_" + v.code, code: v.code!, name, valueType: v.valueType ?? "ENUM",
      unit: v.unit ?? null, usageType: v.usageType ?? "SALE",
      universal: v.universal ?? true, scope: "PLATFORM", sort: v.sort ?? 100,
      status: "ACTIVE", valueCount: 0, inUse: 0, values: [],
    };
    MOCK_SPEC_DIMS.push(row);
    return wait(row);
  },
  archiveSpecDim: (no) => {
    const d = MOCK_SPEC_DIMS.find((x) => x.dimNo === no)!;
    d.status = "ARCHIVED";
    return wait(d);
  },
  unarchiveSpecDim: (no) => {
    const d = MOCK_SPEC_DIMS.find((x) => x.dimNo === no)!;
    d.status = "ACTIVE";
    return wait(d);
  },
  saveSpecValue: async (v) => {
    const dim = MOCK_SPEC_DIMS.find((d) => d.dimNo === v.dimNo);
    if (!dim) notFound("规格项", "Dimension", v.dimNo);
    // QUANT 维度下没有归一量的值排不了序也比不了价 —— 与后端同一条判据
    if (dim!.valueType === "QUANT" && !v.numericValue) {
      fail("这是量纲维度，要填归一后的数值", "Quantitative dimension needs a numeric value");
    }
    const found = dim!.values.find((x) => x.valueNo === v.valueNo);
    if (found) {
      Object.assign(found, v);
      return wait(found);
    }
    const row: SpecValue = {
      valueNo: "SV_" + dim!.code + "_" + v.code, dimNo: dim!.dimNo, code: v.code!,
      label: v.label!, numericValue: v.numericValue ?? null,
      numericUnit: v.numericValue ? dim!.unit ?? null : null,
      aliases: v.aliases ?? [], scope: "PLATFORM", sort: v.sort ?? 100,
      status: "ACTIVE", merchantCount: 0,
    };
    dim!.values.push(row);
    dim!.valueCount = dim!.values.length;
    return wait(row);
  },
  archiveSpecValue: (no) => wait(setValueStatus(no, "ARCHIVED")),
  unarchiveSpecValue: (no) => wait(setValueStatus(no, "ACTIVE")),
  promoteSpecValue: (no) => {
    const v = allMockValues().find((x) => x.valueNo === no)!;
    v.scope = "PLATFORM";
    v.entityNo = null;
    return wait(v);
  },
  saveCategorySpecs: (categoryNo, bindings) => {
    MOCK_CATEGORY_SPECS[categoryNo] = bindings.map((b) => {
      const dim = MOCK_SPEC_DIMS.find((d) => d.dimNo === b.dimNo)!;
      const values = b.valueNos.length
        ? b.valueNos.map((no) => dim.values.find((v) => v.valueNo === no)!).filter(Boolean)
        : dim.values;
      return {
        dimNo: dim.dimNo, code: dim.code, name: dim.name, valueType: dim.valueType,
        unit: dim.unit, usage: b.usageType ?? dim.usageType, universal: dim.universal,
        primary: b.primary, valueCount: values.length,
        values: values.map((v) => ({
          valueNo: v.valueNo, code: v.code, label: b.labels[v.valueNo] ?? v.label,
          numericValue: v.numericValue, numericUnit: v.numericUnit,
        })),
      };
    });
    return productMock.listCategorySpecs();
  },

  /*
   * 类目 × 支付方式 / 积分。**mock 里刻意各留一部分没配** ——
   * 全配满的话，「还有多少类目没配」这条缺口提示永远不出现，等于没做。
   * 与上面 MOCK_CATEGORY_SPECS 只覆盖一部分类目是同一个理由。
   */
  listCategoryPayModes: () =>
    wait(leafCategories().map((cat, i) => ({
      categoryNo: cat.categoryNo,
      categoryName: cat.name,
      parentName: parentNameOf(cat),
      // 每 7 个禁一个，让「被禁」那一行长什么样看得见
      offlineAllowed: i % 7 !== 3,
      configured: i % 7 === 3,
    }))),

  saveCategoryPayMode: (categoryNo, offlineAllowed) => {
    mockPayModes.set(categoryNo, offlineAllowed);
    return productMock.listCategoryPayModes().then((rows) =>
      rows.map((r) => (mockPayModes.has(r.categoryNo)
        ? { ...r, offlineAllowed: mockPayModes.get(r.categoryNo)!, configured: !mockPayModes.get(r.categoryNo)! }
        : r)));
  },

  listCategoryPoints: () =>
    wait(leafCategories().map((cat, i) => {
      const saved = mockPoints.get(cat.categoryNo);
      if (saved) return { categoryNo: cat.categoryNo, categoryName: cat.name, parentName: parentNameOf(cat), ...saved };
      // 三分之一配了规则，其余留空 —— 「没配」是这一页的主角
      const mode = i % 3 === 0 ? "RATIO" : i % 3 === 1 ? "FIXED" : null;
      return {
        categoryNo: cat.categoryNo,
        categoryName: cat.name,
        parentName: parentNameOf(cat),
        earnMode: mode as "FIXED" | "RATIO" | null,
        earnValue: mode === "RATIO" ? 50 : mode === "FIXED" ? 100 : null,
      };
    })),

  saveCategoryPoints: (categoryNo, v) => {
    if (v.earnMode) mockPoints.set(categoryNo, v);
    else mockPoints.delete(categoryNo);
    return productMock.listCategoryPoints();
  },

  listCategorySpecs: () =>
    wait(
      db.categories
        .filter((cat) => cat.level === 2 && !cat.archivedAt)
        .map((cat) => {
          const dims = MOCK_CATEGORY_SPECS[cat.categoryNo] ?? [];
          return {
            categoryNo: cat.categoryNo,
            categoryName: cat.name,
            parentName: db.categories.find((p) => p.categoryNo === cat.parentNo)?.name ?? "",
            categoryType: cat.template,
            dimCount: dims.length,
            dims,
          };
        }),
    ),

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
