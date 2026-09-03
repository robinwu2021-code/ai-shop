// 商品：商品与草稿、类目、规格模板、编码批量导入导出 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import type { GoodsDraft, PublishPreview } from "../contract";
import { db, delay, findGoodsSeed, nextNo, paginate, persist, pick, toGoods } from "@shared/mock/db";
import type { CategoryType, CurrencyCode, Goods, MarketId, SpecTemplate } from "@shared/types";
import { CATEGORY_TYPE, MARKETS, TEMPLATE_TO_TYPE } from "@shared/utils/constants";
import { currentCurrency, money } from "@shared/utils/money";
import {
  csvCell,
  currentStoreNo,
  fillI18n,
  findCategoryTemplate,
  identityRows,
  mockSpecOverride,
  mockState,
  myGoods,
  qualsOf,
  requireMerchant,
  runIdentityImport,
  toI18n,
  withDraftFlag,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const productMock: Pick<MerchantApi,
  "mGoodsList"
  | "mGoodsDetail"
  | "mSaveGoods"
  | "mToggleGoods"
  | "mSaveStock"
  | "mSaveStoreStock"
  | "mSubmitGoods"
  | "mGoodsDraft"
  | "mPublishPreview"
  | "mPublishGoods"
  | "mDiscardGoodsDraft"
  | "mSavePresale"
  | "mSaveStorePrice"
  | "mUploadImage"
  | "mRecognizeGoods"
  | "mDescribeGoods"
  | "mSpuStdSearch"
  | "mCategoryTree"
  | "mSpecTemplates"
  | "mAddSpecValue"
  | "mSkuIdentityExport"
  | "mSkuIdentityPlan"
  | "mSkuIdentityImport"
  | "mAddSpecDim"
  | "mMySpecDims"
  | "mStoreSpecDims"
  | "mDimValues"
  | "mSaveSpecOverride"
  | "mRenameSpecDim"
  | "mArchiveSpecDim"
  | "mPickableDims"
  | "mPickableProps"
  | "mSpecProps"
  | "mQualifications"
  | "mSaveQualification"
  | "mSaveSpecTemplate"
> = {
  // ---------------------------------------------------------------- 商品
  async mGoodsList(q) {
    let list = myGoods();
    /*
     * <b>店级在售投影</b>（后端 `GoodsVO.storeOnSale`，MerchantGoodsServiceImpl）。
     * 主体级的四态（审核中/已驳回）不受门店影响 —— 与后端一致：
     * 那两态是主体的事，标成「本店未上架」会把「等审核」说成「我没上架」。
     * 散列指派而不是随机：同一件货在同一家店每次都一样，刷新不跳。
     */
    const cur = currentStoreNo();
    if (cur) {
      const idx = Math.max(0, db.stores.findIndex((x) => x.storeNo === cur));
      list = list.map((g) => {
        let h = idx;
        for (const ch of String(g.goodsNo ?? g.title ?? "")) h = (h * 31 + ch.charCodeAt(0)) % 100_000;
        // 每家店留一部分不上架，否则「本店未上架」这个状态在 mock 上永远看不见
        return { ...g, storeOnSale: h % 4 !== 0 };
      });
    }
    /*
     * **按四态筛，不是按 onSale 布尔值**。
     *
     * 此前只认 ON_SALE / OFF_SALE 两个值，而 `status` 有四态 ——
     * 「审核中」与「已驳回」两个页签落进 else：不过滤，显示全部。
     * 商家点「已驳回」看到的是所有商品，包括在售的。
     */
    if (q.status === "ON_SALE") list = list.filter((g) => g.onSale);
    else if (q.status === "OFF_SALE") list = list.filter((g) => !g.onSale && !g.status);
    else if (q.status) list = list.filter((g) => g.status === q.status);

    /*
     * **关键词与类目过滤此前完全没实现。**
     *
     * 页面两个都在发（真后端 `GET /biz/goods` 也都支持），而 mock 只看 status ——
     * 于是搜索框输什么都返回全部、类目筛点哪个都返回全部。
     * 这比「筛出 0 条」更难发现：界面一直有内容，看起来在工作。
     *
     * 实测：按 6 个一级类目分别筛，每个都返回同样的 2 条。
     */
    const kw = q.keyword?.trim().toLowerCase();
    if (kw) {
      list = list.filter((g) => String(g.title ?? "").toLowerCase().includes(kw));
    }
    if (q.categoryNo) {
      /*
       * **要连子孙一起匹配**：页面筛的是**一级**类目，而商品挂在二级上 ——
       * 只比对 categoryNo 相等的话，选「食品生鲜」一条也筛不出来。
       */
      /*
       * 先把树摊平成 `{编号, 上级}` 再算，**不在树上做类型断言** ——
       * db 里各层节点的字面量类型并不一致（`parentNo` 一级是 null、二级是 string），
       * 强转会被 TS 拒绝，而绕过它的 `as unknown as` 只是把问题藏起来。
       */
      interface Node { categoryNo: string; parentNo?: string | null; children?: Node[] }
      const flat: Node[] = [];
      const collect = (nodes: Node[]) => {
        for (const c of nodes) {
          flat.push(c);
          if (c.children?.length) collect(c.children);
        }
      };
      collect(db.categories as unknown as Node[]);

      const wanted = new Set<string>([q.categoryNo]);
      // 逐层展开：树最深三层，跑到不再新增为止（比写死轮数稳）
      for (let grew = true; grew; ) {
        grew = false;
        for (const c of flat) {
          if (c.parentNo && wanted.has(c.parentNo) && !wanted.has(c.categoryNo)) {
            wanted.add(c.categoryNo);
            grew = true;
          }
        }
      }
      list = list.filter((g) => wanted.has(g.categoryNo));
    }
    // 商家侧要带 hasDraft：列表页「有未发布修改」徽标的数据源
    return delay(paginate(list.map(withDraftFlag), q.page, q.size));
  },

  async mGoodsDetail(goodsNo) {
    return delay(withDraftFlag(toGoods(findGoodsSeed(goodsNo))));
  },

  async mSaveGoods(payload) {
    const merchantNo = requireMerchant();
    /*
     * **先脱掉响应式外壳再往库里存。**
     *
     * 页面传进来的 `images` / `optionValues` 是 Vue 的 reactive 代理数组
     * （`images: images.value`、`optionValues: r.optionValues` 都是直接给的引用）。
     * 直接存下去有两个后果，第二个更严重：
     *
     *   1. `delay()` 用 `structuredClone` 返回副本，而 Chrome **拒绝克隆 Proxy** ——
     *      于是在 mock 上建完商品，再打开它就抛 DataCloneError，
     *      整个商品详情打不开（实测：goods.images → DataCloneError）；
     *   2. 库里存的是**页面状态的活引用**：商家在编辑页再改一下，
     *      没点保存也已经改到了「数据库」里。真后端不可能有这种事
     *      （HTTP 那条路上一切都经过 JSON 序列化）。
     *
     * 在这个边界上做一次深拷贝，等价于 HTTP 的 JSON 往返 —— 这正是 mock 该模仿的。
     */
    payload = JSON.parse(JSON.stringify(payload)) as typeof payload;
    if (!payload.skus.length) throw new Error("至少要有一个规格");
    // 中文是基准语言：没有它就没有回落目标
    if (!payload.title["zh-CN"].trim()) throw new Error("中文商品名必填");

    // 展示价取最低 SKU 价 —— 列表页「¥12 起」的口径，端上不各算各的
    const price = Math.min(...payload.skus.map((k) => k.price));
    /*
     * 契约里 `priceByMarket` 的键是**市场码**（CN/AE/US），而 mock 库内部一律按
     * **币种**索引（`priceIn()` 拿 currentCurrency 去查）。所以在这个边界上换一次码 ——
     * 与真后端做的是同一件事：它把市场码原样落进 `prd_sku.market`。
     * 不换的话 mock 里查得到、线上查不到，两套实现对同一份契约给出不同结果。
     */
    const toCurrency = (m: MarketId): CurrencyCode =>
      MARKETS.find((x) => x.id === m)!.currency;
    const skuPricesByCurrency = (k: { priceByMarket?: Partial<Record<MarketId, number>> }) =>
      Object.entries(k.priceByMarket ?? {}).reduce<Partial<Record<CurrencyCode, number>>>(
        (acc, [mid, v]) => {
          if (v !== undefined) acc[toCurrency(mid as MarketId)] = v;
          return acc;
        },
        {},
      );
    // 商品级也存一份按市场的展示价：各市场分别取该市场下的最低 SKU 价
    const priceByMarket = MARKETS.reduce<Partial<Record<CurrencyCode, number>>>((acc, m) => {
      const vals = payload.skus
        .map((k) => k.priceByMarket?.[m.id])
        .filter((v): v is number => v !== undefined);
      if (vals.length) acc[m.currency] = Math.min(...vals);
      return acc;
    }, {});
    /*
     * **标准品收敛：mock 也要做一遍**（TDD-标准品库 §3.2）。
     *
     * 真后端在 `save()` 里用标准品的 categoryNo 与 optionCode 覆盖请求值。
     * mock 不做的话就是「mock 上改得掉、连真后端改不掉」—— 而 mock 是开发期
     * 唯一看得见的那份数据，这种错配最难查。
     */
    const std = payload.stdNo ? db.spuStds.find((t) => t.stdNo === payload.stdNo) : undefined;
    if (payload.stdNo && !std) throw new Error("所选标准品不存在");
    const effectiveCategoryNo = std ? std.categoryNo : payload.categoryNo;
    const specSource = std
      ? payload.specGroups.map((g, i) => {
          const sg = std.specGroups[i];
          if (!sg) return g; // 商家追加的规格组：没有对应的标准组，原样保留
          const codes = sg.optionCodes ?? [];
          return { ...g, optionCodes: g.options.map((_, j) => codes[j]) };
        })
      : payload.specGroups;

    const specGroups = specSource.map((g) => ({
      name: toI18n(g.name),
      options: g.options.map(toI18n),
      // 模板编码要跟着落库：不存就等于没做模板 ——
      // 二期想按规格聚合时，历史商品全是自由文本，只能回头刷数据
      optionCodes: g.optionCodes,
      templateNo: g.templateNo,
    }));
    const buildSkus = (existing: { skuNo: string; optionValues: unknown[] }[] = []) =>
      payload.skus.map((k) => ({
        // 复用原 skuNo：历史订单行、购物车、库存流水都引用它，重新生成等于把它们指向不存在的规格
        skuNo:
          k.skuNo ??
          existing.find(
            (e) => e.optionValues.length === k.optionValues.length && e.skuNo === k.skuNo,
          )?.skuNo ??
          nextNo("SK"),
        optionValues: k.optionValues.map(toI18n),
        price: k.price,
        // 分别定价是真源；只填了当前市场时其余市场留空 = 不在那边卖。
        // 契约按市场码来，mock 库按币种存 —— 在这里换码（见上方 toCurrency 的说明）
        priceByMarket: skuPricesByCurrency(k),
        stock: k.stock,
        // 划线价与标称重量：**不传 = 不改**，与真后端同一条规矩。
        // 不落盘的话「mock 上填了、保存后消失」—— 正是这轮在修的那类故障，
        // 只不过发生在 mock 里，而 mock 恰恰是开发期唯一看得见的那一份
        originPrice: k.originPrice,
        nominalGram: k.nominalGram,
        // 成本价同一条规矩：mock 不落盘的话「填了、保存后消失」，
        // 而毛利那行会跟着一起不见 —— 看着像算错了，其实是没存
        costPrice: k.costPrice,
        /*
         * 外部身份三件套（V252）。空串 = 清空。
         *
         * <p>**这里不模拟「不传 = 不改」那一半**：b-app 这三格永远会发
         * （空串或有值），所以 mock 里没有「不传」这个输入。
         * 真后端仍要区分两者 —— 将来的导入接口可能只发改动的那几列。
         * mock 不落盘的话「填了条码、保存后消失」，而 mock 恰恰是开发期
         * 唯一看得见的那一份。
         */
        barcode: k.barcode || undefined,
        merchantSkuCode: k.merchantSkuCode || undefined,
        saleUnit: k.saleUnit || undefined,
      }));

    /**
     * 商品级的可选字段。**「不传 = 不改」逐个字段判空**，与后端 `applyOptional` 同形状。
     *
     * <p>生鲜段 / 服务段按**形态**写：一件大米带上「服务时长 90 分钟」不会报错，
     * 但它会出现在服务类的详情模板里。形态由类目派生，所以两边判的是同一个东西。
     */
    const applyOptional = (seed: Record<string, unknown>, formType: string) => {
      if (payload.limitPerUser !== undefined) seed.limitPerUser = Math.max(payload.limitPerUser, 0);
      if (payload.fresh && formType === CATEGORY_TYPE.FRESH) {
        const f = payload.fresh;
        if (f.cutoffAt !== undefined) seed.cutoffAt = f.cutoffAt;
        if (f.arrivalDesc !== undefined) seed.arrivalDesc = toI18n(f.arrivalDesc);
        if (f.weighed !== undefined) seed.weighed = f.weighed;
        if (f.origin !== undefined) seed.origin = toI18n(f.origin);
      }
      if (payload.service && formType === CATEGORY_TYPE.SERVICE) {
        const sv = payload.service;
        if (sv.durationMin !== undefined) seed.durationMin = sv.durationMin;
        if (sv.storeName !== undefined) seed.storeName = toI18n(sv.storeName);
      }
      if (payload.groupBuy) {
        const gb = payload.groupBuy;
        // 两个都空 = 显式关掉拼团；只填一个后端会拒，这里跟着拒，
        // 否则「mock 上存得下、连真后端报错」
        if (gb.minCount === undefined && gb.price === undefined) {
          seed.groupBuy = undefined;
        } else if (gb.minCount === undefined || gb.price === undefined) {
          throw new Error("起团人数与团购价要一起填");
        } else {
          if (gb.minCount < 2) throw new Error("一个人不叫团，起团人数至少 2");
          seed.groupBuy = { minCount: gb.minCount, price: gb.price };
        }
      }
    };

    if (payload.goodsNo) {
      const seed = findGoodsSeed(payload.goodsNo);
      /*
       * **在售商品的编辑落草稿、种子不动**（双版本，V279）—— 与真后端同一条规矩：
       * 线上照卖旧版，发布时才换版。mock 不做的话，开发期看到的是「保存立刻改线上」，
       * 而真后端从这版起不再是那样 —— 正是最难查的那类 mock/后端错配。
       *
       * 真后端还有一条「保存的内容与线上相同 → 删草稿行」（假标识防线）；
       * mock 不逐字段比对，保守地留着草稿 —— 徽标多显示不会骗人，少显示才会。
       */
      if (seed.onSale && !mockState.publishingDraft) {
        db.goodsDrafts[payload.goodsNo] = payload; // 顶部已深拷贝，存的不是页面活引用
        persist();
        return delay(withDraftFlag(toGoods(seed)));
      }
      seed.title = fillI18n(payload.title);
      seed.subtitle = fillI18n(payload.subtitle);
      // 不传 = 不改，与 images 同一口径：无条件覆盖会让「只改标题」把详情清空
      if (payload.detail !== undefined) seed.detail = payload.detail;
      // 详情图与 images 同一口径：不判空的话，只改标题就把详情图清空
      if (payload.detailImages !== undefined) seed.detailImages = payload.detailImages;
      // 商品参数同一口径：不判空的话，只改标题就把参数清空
      if (payload.params !== undefined) seed.params = payload.params;
      seed.price = price;
      seed.priceByMarket = priceByMarket;
      seed.specGroups = specGroups as (typeof seed.specGroups);
      seed.skus = buildSkus(seed.skus) as (typeof seed.skus);
      /*
       * 形态跟着类目重算 —— 改类目而形态不跟，就又出现了这轮消掉的那种矛盾，
       * 只是换到了 mock 这一侧（而 mock 是开发期唯一看得见的那份数据）。
       */
      const editedType = TEMPLATE_TO_TYPE[findCategoryTemplate(effectiveCategoryNo) ?? ""];
      if (editedType) seed.type = editedType as CategoryType;
      seed.categoryNo = effectiveCategoryNo;
      // 溯源：不传 = 脱离标准品（与真后端一致，不是「不改」）
      seed.stdNo = payload.stdNo;
      // 「不传 = 不改」，与后端一致：不判空的话，改一次标题就把轮播图/履约方式清空
      if (payload.images !== undefined) seed.images = payload.images;
      if (payload.fulfillments !== undefined) {
        if (!payload.fulfillments.length) throw new Error("至少选一种履约方式");
        seed.fulfillments = payload.fulfillments as (typeof seed.fulfillments);
      }
      applyOptional(seed as unknown as Record<string, unknown>, seed.type);
      persist();
      return delay(toGoods(seed));
    }

    const goodsNo = nextNo("G");
    const newType = (TEMPLATE_TO_TYPE[findCategoryTemplate(effectiveCategoryNo) ?? ""] ??
      CATEGORY_TYPE.NORMAL) as CategoryType;
    const seed = {
      goodsNo,
      merchantNo,
      // 形态由类目派生，与真后端同一条规则（P1-1）—— mock 自己算一遍，
      // 而不是抄 payload：payload 里已经没有 type 了，而「mock 上能建、
      // 连真后端就变成另一种货」是最难查的一类错配
      type: newType,
      categoryNo: effectiveCategoryNo,
      stdNo: payload.stdNo,
      title: fillI18n(payload.title),
      subtitle: fillI18n(payload.subtitle),
      cover: payload.cover || "📦",
      detail: payload.detail,
      detailImages: payload.detailImages ?? [],
      params: payload.params ?? [],
      // 端上没传就给一个占位，传了就用他上传的那几张
      images: payload.images?.length ? payload.images : ["📦"],
      fulfillments: payload.fulfillments?.length ? payload.fulfillments : ["STORE_PICKUP"],
      price,
      priceByMarket,
      /*
       * **新建落草稿、不在售**（批 D）：mock 此前直接给 onSale: true，
       * 于是「录完就能卖」在开发期看着完全正常，而真后端一直是「录完要过审」。
       * 两边不同的后果是端上按 mock 的样子做交互，接真后端才发现少了两步。
       */
      status: "DRAFT" as const,
      onSale: false,
      salesCount: 0,
      specGroups,
      skus: buildSkus(),
      promotions: [],
    } as unknown as (typeof db.goodsSeeds)[number];
    applyOptional(seed as unknown as Record<string, unknown>, newType);
    db.goodsSeeds.unshift(seed);
    persist();
    return delay(toGoods(findGoodsSeed(goodsNo)));
  },

  async mToggleGoods(goodsNo, onSale) {
    const seed = findGoodsSeed(goodsNo);
    seed.onSale = onSale;
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStock(goodsNo, skuNo, stock) {
    const seed = findGoodsSeed(goodsNo);
    const sku = seed.skus.find((s) => s.skuNo === skuNo);
    if (!sku) throw new Error("规格不存在");
    sku.stock = stock;
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStoreStock(goodsNo, skuNo, stock) {
    /*
     * mock 里没有门店维度的库存表 —— 单店是 mock 的默认形态，
     * 而门店级库存要在真后端上才谈得上。这里与 mSaveStock 同行为：
     * 让端上的交互能跑通，真实语义（没设库存的店视为 0）由后端用例守。
     */
    return this.mSaveStock(goodsNo, skuNo, stock);
  },

  async mSubmitGoods(goodsNo) {
    const seed = findGoodsSeed(goodsNo);
    // 只有草稿会动 —— 重复点击是常态，报错只会让商家以为提交失败
    if (seed.status === "DRAFT") seed.status = "PENDING";
    persist();
    return delay(toGoods(seed));
  },

  // ---- 双版本发布（V279）

  async mGoodsDraft(goodsNo) {
    // 无草稿回 null 是常态（编辑页转而读线上），与真后端同一口径
    return delay((db.goodsDrafts[goodsNo] as GoodsDraft | undefined) ?? null);
  },

  async mPublishPreview(goodsNo) {
    const draft = db.goodsDrafts[goodsNo] as GoodsDraft | undefined;
    if (!draft) throw new Error("没有待发布的修改");
    const seed = findGoodsSeed(goodsNo);
    /*
     * mock 只演**形状**：几行看得懂的字段级差异，让发布确认页有东西可渲染。
     * 真 diff 在服务端 dry-run 烘焙后算 —— 「商家没碰规格、文案仍随规格库刷新」
     * 那类差异只有后端算得出来（这正是 diff 不放端上的原因），mock 不假装会。
     */
    const changes: PublishPreview["changes"] = [];
    const push = (field: string, label: string, before: string, after: string) => {
      if (before !== after) changes.push({ field, label, before, after });
    };
    push("title", "标题", pick(seed.title), draft.title["zh-CN"] ?? "");
    push("subtitle", "副标题", pick(seed.subtitle), draft.subtitle["zh-CN"] ?? "");
    push("cover", "封面", seed.cover, draft.cover ?? "");
    push(
      "spec", "规格",
      seed.specGroups.map((g) => `${pick(g.name)}（${g.options.map(pick).join(" / ")}）`).join("；"),
      draft.specGroups.map((g) => `${g.name}（${g.options.join(" / ")}）`).join("；"),
    );
    push(
      "price", "价格",
      money(Math.min(...seed.skus.map((k) => k.price))),
      money(Math.min(...draft.skus.map((k) => k.price))),
    );
    // mock 单人使用：没有「别人改过线上」，stale 恒 false、baseVersion 给个定值；
    // 冲突确认那条链（80018 → 确认 → 放行）只有真后端演得了，场景测试守着
    return delay({ changes, blocked: [], stale: false, baseVersion: 1 } satisfies PublishPreview);
  },

  async mPublishGoods(goodsNo) {
    const draft = db.goodsDrafts[goodsNo] as GoodsDraft | undefined;
    if (!draft) throw new Error("没有待发布的修改");
    /*
     * 复用 mSaveGoods 的整条落库路径换版（重入标志见 publishingDraft）——
     * 不另写第二套写入逻辑，与真后端 swapFromDraft 同一个手法。
     * mock 不模拟审核开的那半（提交待审、线上继续卖旧版）：审核队列在运营端，
     * b-app 的 mock 里没有那个视角，两态语义由后端场景测试守。
     */
    mockState.publishingDraft = true;
    try {
      await this.mSaveGoods(draft);
    } finally {
      mockState.publishingDraft = false;
    }
    delete db.goodsDrafts[goodsNo];
    persist();
    return delay(withDraftFlag(toGoods(findGoodsSeed(goodsNo))));
  },

  async mDiscardGoodsDraft(goodsNo) {
    // 幂等：没有草稿也不报错 —— 重复点「放弃」是常态，与真后端同一口径
    delete db.goodsDrafts[goodsNo];
    persist();
    return delay(withDraftFlag(toGoods(findGoodsSeed(goodsNo))));
  },

  async mSavePresale(goodsNo, cutoffAt, arrivalDesc) {
    const seed = findGoodsSeed(goodsNo);
    if (seed.type !== "FRESH") throw new Error("只有生鲜有截单时间");
    if (cutoffAt != null) seed.cutoffAt = cutoffAt;
    // 种子里这一列是多语言（与 origin 同）—— 商家填的是一句中文，回落到三语
    if (arrivalDesc != null) seed.arrivalDesc = toI18n(arrivalDesc);
    /*
     * ★ **不动 status** —— 这正是它与 mSaveGoods 的分界。
     * mock 也照此实现：改成回待审的话，「改截单会不会下架」这个最要紧的问题
     * 在开发期得到的是错误答案。
     */
    persist();
    return delay(toGoods(seed));
  },

  async mSaveStorePrice(goodsNo, skuNo, price) {
    const seed = findGoodsSeed(goodsNo);
    const sku = seed.skus.find((k) => k.skuNo === skuNo);
    if (!sku) throw new Error("规格不存在");
    if (price != null && price < 0) throw new Error("价格不能为负");
    /*
     * **空 = 取消本店单独定价**，回到主体价 —— 不是改成 0。
     * mock 也要照此实现：写成 0 的话「取消定价」这条路在开发期看着像「白送」。
     */
    sku.storePrice = price ?? undefined;
    persist();
    return delay(toGoods(seed));
  },

  // ---------------------------------------------------------------- 图片与识别
  async mUploadImage(tempPath) {
    // mock 直接把端上的临时路径当 URL 用 —— H5 下 blob: 路径能直接显示。
    // 真实环境：小程序走 uni.uploadFile（域名要在白名单），App 无此限制；
    // 服务端返回 CDN URL（E9）
    if (!tempPath) throw new Error("没有选到图片");
    return delay({ url: tempPath }, 400);
  },

  async mRecognizeGoods() {
    // ⚠️ **这是假识别**：mock 里没有模型，按当前时间在几个常见品类里轮换，
    // 只为把「识别 → 预填 → 店主改 → 保存」这条交互链路跑通。
    // 真实实现在服务端（小程序不能跑本地模型），置信度由模型给。
    const guesses: { title: string; subtitle: string; type: Goods["type"]; categoryNo: string }[] = [
      { title: "东北五常大米 10斤装", subtitle: "当季新米 颗粒饱满", type: CATEGORY_TYPE.NORMAL, categoryNo: "CAT131" },
      { title: "本地土鸡蛋 30枚", subtitle: "当日现捡 冷链直达", type: CATEGORY_TYPE.FRESH, categoryNo: "CAT130" },
      { title: "洗衣液 大容量装 3kg", subtitle: "深层洁净 低泡易漂", type: CATEGORY_TYPE.NORMAL, categoryNo: "CAT210" },
    ];
    const g = guesses[db.seq % guesses.length]!;
    return delay({ ...g, confidence: 0.72 }, 700);
  },

  /**
   * 自动生成图文详情。**这是假生成**：mock 里没有模型，按标题套一个模板。
   *
   * 保留它的意义是把「点按钮 → 转圈 → 文字进框 → 商家改 → 保存」整条链路跑通，
   * 包括**没填标题时应当拒绝**这一档 —— 真实实现里模型没有名字只能瞎编，
   * 所以那一档在服务端也是拒绝，不该只在真机上才发现。
   */
  async mDescribeGoods(req) {
    if (!req.title?.trim()) return delay({ detail: "" }, 300);
    const lines = [
      `· ${req.subtitle?.trim() || req.title.trim()}，适合日常家庭采买。`,
      "· 规格与分量以商品页所列为准，下单后按规格备货。",
      "· 建议收到后尽快食用或使用，开封后请按包装说明保存。",
      "· 如遇缺货或规格调整，我们会在发货前与你确认。",
    ];
    return delay({ detail: lines.join("\n") }, 900);
  },

  // ---------------------------------------------------------------- 标准品库
  /**
   * 标准品搜索。**mock 自己也做一遍收敛**（见 mSaveGoods）——
   * 「mock 上建出来是这样、连真后端变成那样」是这套 mock 最该防的错配。
   */
  async mSpuStdSearch(q) {
    const kw = (q.keyword ?? "").trim();
    const rows = db.spuStds
      .filter((t) => t.status !== "ARCHIVED")
      .filter((t) => !q.categoryNo || t.categoryNo === q.categoryNo)
      // 标题与别名一起搜：商家嘴里的「洋芋」与标准品标题「土豆」对不上时，
      // 结果不是报错，是他以为标准库里没有 —— 然后自建一个，可比性在这一次就丢了
      .filter((t) => !kw || t.title.includes(kw) || (t.keywords ?? "").includes(kw))
      .slice(0, q.limit && q.limit > 0 ? q.limit : 20);
    return delay(rows.map((t) => ({ ...t })));
  },

  // ---------------------------------------------------------------- 类目
  async mCategoryTree() {
    // 直接给整棵树：类目就几十条且极少变，分层拉取只会让选择器多两次等待
    return delay(db.categories.map((c) => ({ ...c })));
  },

  // ---------------------------------------------------------------- 规格模板
  /**
   * 规格模板。**mock 必须把两层的取舍也做一遍** ——
   * 「mock 上推荐成这样、连真后端变成那样」是这套 mock 最该防的错配。
   *
   * 两层：`categoryNo` 为空的是品类兜底，填了的是类目专属。
   * 类目专属排前面，并用**同名**规格组顶掉兜底那条
   * （休闲零食的「重量」应当替代普通实物的「规格」，不是两个都推）。
   */
  /**
   * 规格模板。**与后端 `MerchantGoodsServiceImpl.specTemplates` 同一条规矩**：
   *
   * <ul>
   *   <li>选了类目 → 只给这一类配好的（类目级）+ 商家自存的常用，
   *       <b>不回落品类兜底</b> —— 兜底会把运营端的缺口盖住，而且推给谁都不对题
   *   <li>还没选类目 → 只给商家自存的常用（选完才知道该推什么）
   *   <li>本店覆盖当场生效：停用的维度整条不下发、本店叫法换过、停掉的档位剔掉
   * </ul>
   */
  async mSpecTemplates(categoryType, categoryNo) {
    const merchantNo = db.merchant.merchantNo;
    const picked = categoryNo?.trim() || undefined;
    const mine = db.specTemplates.filter(
      (tpl) => tpl.scope === "MERCHANT" && tpl.merchantNo === merchantNo,
    );
    if (!picked) return delay(mine);

    const ov = mockSpecOverride.get(picked) ?? [];
    const catLevel = db.specTemplates
      .filter((tpl) => tpl.scope === "PLATFORM" && tpl.categoryNo === picked)
      .filter((tpl) => {
        const o = ov.find((x) => x.dimNo === tpl.templateNo);
        // 本店停用的维度：整条不下发（连带它下面的档位一起消失）
        return !o || o.enabled;
      })
      .map((tpl) => {
        const o = ov.find((x) => x.dimNo === tpl.templateNo);
        if (!o) return tpl;
        const offCodes = new Set(o.values.filter((v) => !v.enabled).map((v) => v.code));
        return {
          ...tpl,
          // 本店叫法优先 —— 只换展示，templateNo 不变，跨店聚合照常
          name: o.label?.trim() || tpl.name,
          // 停掉的档位**不下发**，而不是带个 false 让端上过滤：下发了就有可能显示出来
          options: tpl.options.filter((x) => !offCodes.has(x.code ?? x.label)),
        };
      })
      .filter((tpl) => tpl.options.length > 0);

    /*
     * **他自己加进来的规格**：类目没绑，但他在「商品规格」页里加了。
     * 与后端 `templatesForCategory` 的最后一段同一件事 ——
     * 不看这一段的话，「＋ 加规格」加完什么都不会发生（读侧根本不看它），
     * 而那正是 4119ae84 在后端修掉的那个形状。
     */
    const shown = new Set(catLevel.map((t) => t.templateNo));
    const added = ov
      .filter((o) => o.enabled && !shown.has(o.dimNo))
      .map((o) => {
        const tpl = db.specTemplates.find((t) => t.templateNo === o.dimNo);
        if (!tpl) return undefined;
        const offCodes = new Set(o.values.filter((v) => !v.enabled).map((v) => v.code));
        return {
          ...tpl,
          categoryNo: picked,
          name: o.label?.trim() || tpl.name,
          options: tpl.options.filter((x) => !offCodes.has(x.code ?? x.label)),
        };
      })
      .filter((x): x is NonNullable<typeof x> => !!x && x.options.length > 0);

    /*
     * **挑了类目就不再把自建规格整份倒出来。**后端这条路是 forCategory：
     * 类目绑定 + 本店覆盖 + 他加进这个类目的，没有「我建过的全都算」这一档。
     * mock 多给的话，任何按这份结果回写覆盖的调用方（建品页加参数走的就是这条），
     * 都会把毫不相干的自建规格一并挂到这个类目下 —— 而他从没这么说过。
     * 自建规格作为**候选**出现在哪里，由 mPickableDims 回答，不是这里。
     */
    return delay([...catLevel, ...added]);
  },

  /**
   * 在平台维度下加一个自有值。mock 里只做两件真会影响界面的事：
   * 撞车直接回平台那一档、量纲维度抽不出数字就拒。
   */
  async mAddSpecValue(dimNo, label) {
    requireMerchant();
    const text = label.trim();
    if (!text) throw new Error("先填规格值");
    /*
     * **两张表都要找。** 商品参数（db.specProps）也会走这条路：
     * 建品页给「海拔」填一个值时 dimNo 指的是一个 PROP 维度，
     * 只找 specTemplates 的话 tpl 是 undefined —— 于是值静静地没落到任何地方，
     * 而接口返回成功。后端那侧是同一张 prd_spec_dim，不存在这个分叉。
     */
    const tpl = db.specTemplates.find((t) => t.templateNo === dimNo)
      ?? db.specProps.find((t) => t.templateNo === dimNo);
    const hit = tpl?.options.find((o) => o.label === text);
    if (hit) return delay({ valueNo: dimNo + "_" + (hit.code ?? text), code: hit.code ?? "", label: hit.label });
    /*
     * 量纲维度：文案里得写着数量，否则这一档排不了序也比不了价。
     * 措辞与后端 `SPEC_VALUE_NEEDS_QUANTITY` 对齐 —— 两处不一致的话，
     * 在 mock 上验过的提示到真机上会变成另一句（此前真机上是「请求参数有误」）。
     */
    if (/重量|容量|长度|口径|净含量/.test(tpl?.name ?? "") && !/\d/.test(text)) {
      throw new Error("这一档要写清数量，例如 750g 或 1.5kg");
    }
    /*
     * **新建的值也要有 code。**此前 push 进去的是 `{ label }`（没有 code），
     * 返回的也是 `code: ""` —— 于是端上只能退回用 valueNo 当 code，
     * 而下一次再取候选时值池里那一条仍然没有 code，两边对不上：
     * 同一个「中辣」既算「已经有了」又算「还能加」，连点两下就出现两个。
     * 后端那侧每个值都有 valueNo，不存在没编码的值。
     */
    const code = dimNo + "_M" + ((tpl?.options.length ?? 0) + 1);
    tpl?.options.push({ code, label: text });
    return delay({ valueNo: code, code, label: text });
  },

  // ---- 商品编码批量导入导出（P4）
  //
  // **规则与后端 SkuIdentityServiceImpl 逐条对齐**，尤其是那三行安全边界：
  // 整列缺席 = 不碰、空格子 = 不改、`-` = 清空。
  // mock 上宽松一分，商家就会在 mock 里验出一个后端不认的用法。

  async mSkuIdentityExport() {
    const rows = identityRows();
    const head = ["skuNo", "商品", "规格", "条码", "货号", "单位"].join(",");
    const body = rows.map((r) => [
      r.skuNo, csvCell(r.goods), csvCell(r.spec),
      csvCell(r.barcode), csvCell(r.code), csvCell(r.unit),
    ].join(",")).join("\n");
    // BOM：不带的话 Excel 按 GBK 读，表头直接是乱码
    return delay({ csv: "\ufeff" + head + "\n" + body + "\n" });
  },

  async mSkuIdentityPlan(csv) {
    return delay(runIdentityImport(csv, false));
  },

  async mSkuIdentityImport(csv) {
    return delay(runIdentityImport(csv, true));
  },

  /** 自建维度：只在本店可用，不参与跨店比价 */
  async mAddSpecDim(name, labels, usageType) {
    const merchantNo = requireMerchant();
    const nm = name.trim();
    if (!nm) throw new Error("先填规格名");
    if (["规格", "型号", "类型", "属性", "参数"].includes(nm)) {
      throw new Error("「" + nm + "」太泛，换一个说清楚是什么的名字");
    }
    /*
     * 与真后端同两条兜底：
     *   · **与平台维度重名 → 直接给平台那个**。他要的是「按这个维度分规格」，
     *     不是「拥有一个自己的颜色」—— 后者只会让他的货从跨店聚合里掉出去。
     *   · **与自己已建的重名 → 复用**。实测踩过：点两次「自定义规格」都输「辣度」，
     *     库里就有两个同名维度，而建品页会并排列出两个，他分不出该选哪个。
     * mock 不照做的话，开发期永远看不到这两种合并，而它们正是这条路最容易撞上的。
     */
    /*
     * **建到哪张表跟着 usageType 走。** mock 里销售规格与商品参数是两份
     * （与后端两条端点同构），一律往 specTemplates 里塞的话，
     * 在「商品参数」栏建出来的东西会出现在规格那一栏 —— 而它本该是参数。
     */
    const into = usageType === "PROP" ? db.specProps : db.specTemplates;
    const hit = into.find(
      (t) => t.name === nm && (t.scope === "PLATFORM" || t.merchantNo === merchantNo),
    );
    if (hit) return delay(hit);

    const created: SpecTemplate = {
      templateNo: nextNo("SD"),
      scope: "MERCHANT",
      merchantNo,
      name: nm,
      options: labels.map((l) => l.trim()).filter(Boolean).map((label) => ({ label })),
    };
    into.push(created);
    return delay(created);
  },

  /**
   * 「我建的规格」。**这一页已经不直接用它了**（自建规格现在并进类目卡显示），
   * 留着是因为「加规格」面板要拿它算配额：已建几个 / 上限几个。
   */
  async mMySpecDims() {
    const merchantNo = db.merchant.merchantNo;
    const own = db.specTemplates.filter((t) => t.scope === "MERCHANT" && t.merchantNo === merchantNo);
    return delay(own.map((t) => ({
      dimNo: t.templateNo,
      name: t.name,
      valueCount: t.options.length,
      usedCount: myGoods().filter((g) => (g.specGroups ?? []).some((x) => x.name === t.name)).length,
      status: "ACTIVE" as const,
      dimUsed: own.length,
      dimQuota: 10,
      valueQuota: 20,
      values: t.options,
    })));
  },

  async mStoreSpecDims(storeNo) {
    // mock 里按门店货架分组：与真后端同一个形状，值取该类目的模板
    // 不传就用第一家店 —— mock 的演示会话只有一家在用
    const key = storeNo || db.stores[0]?.storeNo || "";
    const cats = db.storeCategories[key] ?? [];
    /*
     * **要套用本店覆盖。** 真后端的 dimsByStore 走的是 templatesForCategory，
     * 覆盖（停用 / 改名 / 停档位）天然生效；mock 这里此前直接读模板表，
     * 于是「移除一个规格」→ 重新取一次 → 它又回来了，而后端其实已经记下了。
     * 这类 mock 与后端的分歧最难查：页面上看着像功能没做。
     */
    const applyOv = (list: typeof db.specTemplates, categoryNo: string) => {
      const ov = mockSpecOverride.get(categoryNo) ?? [];
      const dress = (t: (typeof list)[number]) => {
        const o = ov.find((x) => x.dimNo === t.templateNo);
        if (!o) return t;
        const off = new Set(o.values.filter((v) => !v.enabled).map((v) => v.code));
        return {
          ...t,
          name: o.label?.trim() || t.name,
          options: t.options.filter((x) => !off.has(x.code ?? x.label)),
        };
      };
      const bound = list
        .filter((t) => t.categoryNo === categoryNo)
        .filter((t) => {
          const o = ov.find((x) => x.dimNo === t.templateNo);
          return !o || o.enabled;
        })
        .map(dress);
      /*
       * **他自己加进来的规格也要给** —— 与后端 forCategory 里 `ov.addedDims()`
       * 那一段对齐（SpecLibraryServiceImpl）。类目没绑但他在「我的规格」里加了：
       * 只认类目绑定的话，加进来的规格落了库却永远不显示，界面上就是
       * 「点了 ＋ 选了一个，什么都没发生」—— 而后端其实已经记下了。
       * 自建规格必然走这条路（它天生不绑任何类目），于是整条自建链路在 mock 上
       * 看起来像没做完，实测就这么绕了一圈。
       */
      const shown = new Set(bound.map((t) => t.templateNo));
      const added = ov
        .filter((o) => o.enabled && !shown.has(o.dimNo))
        .map((o) => list.find((t) => t.templateNo === o.dimNo))
        .filter((t): t is (typeof list)[number] => !!t)
        .map(dress);
      // 排过序的在前，没排过的跟在后面 —— 拖动排序在 mock 上也要看得出生效
      const at = (no: string) => {
        const i = ov.findIndex((o) => o.dimNo === no);
        return i < 0 ? Number.MAX_SAFE_INTEGER : i;
      };
      return [...bound, ...added].sort((a, b) => at(a.templateNo) - at(b.templateNo));
    };
    return delay(cats.map((c) => ({
      categoryNo: c.categoryNo,
      categoryName: c.name,
      dims: applyOv(db.specTemplates, c.categoryNo),
      // 商品参数与销售规格并排下发 —— 与真后端 StoreCategorySpecVO 同一形状
      props: applyOv(db.specProps, c.categoryNo),
    })));
  },

  /**
   * mock 的覆盖只做到「看得出生效」：按提交的顺序与启用重排模板的 options。
   * 不落库 —— mock 没有覆盖表，而这一步真正要验的是端上提交的形状对不对。
   */
  async mDimValues(dimNo) {
    /*
     * mock 里模板表就是值池：这个维度的全部档位。
     * **两张表都要找** —— 商品参数在 db.specProps 里，只找 specTemplates 的话
     * 参数那侧的候选永远是空的，界面上就是「平台没给这一项配可选值」，
     * 而平台明明配了。后端那侧是同一张 prd_spec_dim，不存在这个分叉。
     */
    const t = db.specTemplates.find((x) => x.templateNo === dimNo)
      ?? db.specProps.find((x) => x.templateNo === dimNo);
    return delay(t?.options ?? []);
  },

  async mSaveSpecOverride(categoryNo, dims) {
    /*
     * **真的存下来**（此前只把提交内容原样回一份，刷新即失）。
     * 存了之后 `mSpecTemplates` 才看得到它 —— 而「建品页跟着本店口径变」
     * 正是这条链路的全部意义，不落库就等于在 mock 上把它藏起来了。
     */
    mockSpecOverride.set(categoryNo, dims.map((d) => ({
      dimNo: d.dimNo,
      enabled: d.enabled !== false,
      label: d.label,
      values: (d.values ?? []).map((v) => ({ code: v.code, enabled: v.enabled !== false })),
    })));
    // 回最新的合并结果：端上照它重渲染，与真后端同一个约定
    return this.mSpecTemplates(undefined, categoryNo);
  },

  async mRenameSpecDim(dimNo, name) {
    const t = db.specTemplates.find((x) => x.templateNo === dimNo);
    if (!t) throw new Error("规格不存在");
    // 与真后端同一条：撞平台维度名不给改 —— 换个名字也不会让它变成平台维度
    if (db.specTemplates.some((x) => x.scope === "PLATFORM" && x.name === name.trim())) {
      throw new Error("平台已有同名规格，直接在建品页里挑它");
    }
    t.name = name.trim();
    return delay(undefined);
  },

  async mArchiveSpecDim(dimNo, archived) {
    const t = db.specTemplates.find((x) => x.templateNo === dimNo);
    if (!t) throw new Error("规格不存在");
    // mock 的模板表没有 status 字段：停用就从可挑清单里摘掉，效果与真后端一致
    if (archived) db.specTemplates = db.specTemplates.filter((x) => x.templateNo !== dimNo);
    return delay(undefined);
  },

  /**
   * 能挑的维度。mock 里的规格库只有模板表这一份，所以分组的判据与真后端一致：
   * 本类目的（categoryNo 命中）→ 平台通用（无 categoryNo）→ 自建（scope=MERCHANT）。
   */
  async mPickableDims(categoryNo) {
    const merchantNo = db.merchant.merchantNo;
    const picked = categoryNo?.trim() || undefined;
    const cat = picked ? db.specTemplates.filter((t) => t.categoryNo === picked) : [];
    const seen = new Set(cat.map((t) => t.templateNo));
    const universal = db.specTemplates.filter(
      (t) => t.scope === "PLATFORM" && !t.categoryNo && !seen.has(t.templateNo),
    );
    universal.forEach((t) => seen.add(t.templateNo));
    const mine = db.specTemplates.filter(
      (t) => t.scope === "MERCHANT" && t.merchantNo === merchantNo && !seen.has(t.templateNo),
    );
    return delay([...cat, ...universal, ...mine]);
  },

  /**
   * 这一类的商品参数。**只按类目给**，不像销售规格那样还兜底通用池 ——
   * 参数是「这一类的货该标什么」，跨类目摊开毫无意义
   * （给一袋菜推荐「功率」）。
   */
  /**
   * 还能加进这一类的参数：本类目已配 → 平台通用（mock 里就是别的类目那几条去重）。
   * 与 mPickableDims 同一形状 —— 端上那一段代码两栏共用，形状不同就得分叉。
   */
  async mPickableProps(categoryNo) {
    const picked = categoryNo?.trim();
    const cat = picked ? db.specProps.filter((t) => t.categoryNo === picked) : [];
    /*
     * 按名字去重：同一个「产地」在几个类目下各有一条，摆三遍毫无意义。
     * ⚠️ 别写成 `!seen.has(n) && !seen.add(n)` —— `Set.add` 返回 Set（真值），
     * 那个 `!` 恒为 false，于是候选**永远是空的**（我刚踩过）。
     */
    const seen = new Set(cat.map((t) => t.name));
    const rest = [];
    for (const t of db.specProps) {
      if (seen.has(t.name)) continue;
      seen.add(t.name);
      rest.push(t);
    }
    return delay([...cat, ...rest]);
  },

  async mSpecProps(categoryNo) {
    const picked = categoryNo?.trim();
    if (!picked) return delay([]);
    const ov = mockSpecOverride.get(picked) ?? [];
    const bound = db.specProps
      .filter((t) => t.categoryNo === picked)
      .filter((t) => {
        const o = ov.find((x) => x.dimNo === t.templateNo);
        return !o || o.enabled;
      })
      .map((t) => {
        const o = ov.find((x) => x.dimNo === t.templateNo);
        if (!o) return t;
        const off = new Set(o.values.filter((v) => !v.enabled).map((v) => v.code));
        return {
          ...t,
          name: o.label?.trim() || t.name,
          options: t.options.filter((x) => !off.has(x.code ?? x.label)),
        };
      });
    /*
     * **他自己加进来的参数**（建品页的「＋ 加参数」、「商品规格和参数」里加的）：
     * 类目没绑，但覆盖里有它。与 mSpecTemplates 最后那一段同一件事。
     *
     * <p>与规格那侧唯一的差别：**一个值都没有也要给出来**。
     * 刚建出来的参数必然是这个样子（建的时候只填了名字），
     * 按「空的就跳过」处理的话，他加完什么都不会发生 —— 而后端照给
     * （SpecLibraryServiceImpl：空档位只跳过 SALE，PROP 照发）。
     */
    const shown = new Set(bound.map((t) => t.templateNo));
    const added = ov
      .filter((o) => o.enabled && !shown.has(o.dimNo))
      .map((o) => {
        const t = db.specProps.find((x) => x.templateNo === o.dimNo);
        if (!t) return undefined;
        const off = new Set(o.values.filter((v) => !v.enabled).map((v) => v.code));
        return {
          ...t,
          categoryNo: picked,
          name: o.label?.trim() || t.name,
          options: t.options.filter((x) => !off.has(x.code ?? x.label)),
        };
      })
      .filter((x): x is NonNullable<typeof x> => !!x);
    return delay([...bound, ...added]);
  },

  /**
   * 我的资质。mock 里给一条「已传但还没授码」的样本 ——
   * 那正是这一页要说清楚的状态：传了 ≠ 解锁了。
   */
  async mQualifications(entityNo) {
    requireMerchant();
    return delay({
      items: qualsOf(entityNo),
      grantedCodes: ["FRESH_VEG", "FRESH_FRUIT"],
      catalog: [
        { code: "FRESH_VEG", name: "蔬菜", requiredQualification: "营业执照（食用农产品）",
          qualType: "BUSINESS_LICENSE" as const, categoryNames: ["蔬菜"] },
        { code: "FRESH_FRUIT", name: "水果", requiredQualification: "营业执照（食用农产品）",
          qualType: "BUSINESS_LICENSE" as const, categoryNames: ["水果"] },
        { code: "FOOD", name: "熟食加工", requiredQualification: "食品经营许可证",
          qualType: "FOOD_PERMIT" as const, categoryNames: ["熟食卤味"] },
        { code: "FRESH_MEAT", name: "肉禽蛋", requiredQualification: "食品经营许可证",
          qualType: "FOOD_PERMIT" as const, categoryNames: ["肉禽蛋", "水产海鲜"] },
        { code: "DAILY", name: "日用百货", requiredQualification: null,
          qualType: null, categoryNames: ["纸品清洁", "家居用品"] },
      ],
    });
  },

  async mSaveQualification(payload) {
    requireMerchant();
    if (!payload.qualName?.trim()) throw new Error("先填证件名称");
    // ★ 传到**哪张证照**上。共用一份的话，「看的是第二张、传到第一张」这个错
    // 在 mock 下永远看不出来 —— 而那正是 entityNo 这个参数要防的事
    const bucket = qualsOf(payload.entityNo);
    const created = {
      qualNo: nextNo("QL"),
      qualType: payload.qualType,
      qualName: payload.qualName.trim(),
      qualNumber: payload.qualNumber ?? null,
      imageUrl: payload.imageUrl ?? null,
      expireAt: payload.expireAt ?? null,
      status: "VALID",
    };
    bucket.push(created);
    /*
     * **落盘**。原先这里漏了 persist()，表现是「传完看得见、刷新就没了」——
     * db.ts 顶上那段注释点名的正是这一类漏配（已经因此漏过三次）。
     * 多证照之后它更要紧：第二张证照的证件只存在这一个桶里，
     * 不落盘的话「传到哪张证照上」这件事刷新一次就看不出来了。
     */
    persist();
    return delay(created);
  },

  async mSaveSpecTemplate(payload) {
    const merchantNo = requireMerchant();
    const options = payload.options.map((o) => o.trim()).filter(Boolean);
    if (!payload.name.trim() || !options.length) throw new Error("规格名和选项都要填");

    // 商家自存的模板**不给 code**：code 的意义是跨商家统一口径，
    // 各家自己起的编码互不相通，给了反而制造「看起来能聚合其实不能」的假象
    const created: SpecTemplate = {
      templateNo: nextNo("ST"),
      scope: "MERCHANT",
      merchantNo,
      name: payload.name.trim(),
      options: options.map((label) => ({ label })),
    };
    db.specTemplates.push(created);
    persist();
    return delay({ ...created });
  },
};
