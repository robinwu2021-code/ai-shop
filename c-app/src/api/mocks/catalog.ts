// 商品与购物车 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import type { GoodsQuery } from "../contract";
import { allCommunitySeeds, allGoods, buildGroupBuy, db, delay, findGoodsSeed, paginate, persist, toGoods } from "@shared/mock/db";
import { defaultFulfillment } from "@shared/utils/goods";
import { buyNGetM, giftQtyFor } from "@shared/utils/promotion";
import {
  distanceOf,
  reaches,
} from "./_shared";
import type { ShopApi } from "../contract";


/**
 * 把购物车行按**当前商品状态**重算一遍：标题/规格的语言快照、赠品件数、
 * 失效标记与可售库存。
 *
 * <p>四个购物车接口都要走它。**此前只有 `cartList` 做这件事**，
 * 于是改一次数量返回的那份就少了这些派生字段 —— 页面上的表现是
 * 「点一下加号，失效提示消失了」，而商品并没有重新上架。
 *
 * <p>失效与库存的口径与后端 `CartServiceImpl.list()` 一致：
 * 商品下架或 SKU 查不到即 `invalid`，`available` 取 SKU 的可售库存。
 */
function refreshCart() {
  return db.cart.map((it) => {
    const g = toGoods(findGoodsSeed(it.goodsNo));
    const sku = g.skus.find((s) => s.skuNo === it.skuNo);
    // 赠品件数由促销规则实时算，不存库 —— 存下来会与规则漂移
    const promo = buyNGetM(g.promotions);
    return {
      ...it,
      title: g.title,
      spec: sku?.spec ?? it.spec,
      giftQty: giftQtyFor(promo, it.qty),
      giftLabel: promo ? `${promo.buyN}+${promo.giftM}` : undefined,
      invalid: !g.onSale || !sku,
      available: sku?.stock ?? 0,
    };
  });
}

export const catalogMock: Pick<ShopApi,
  "goodsList"
  | "goodsDetail"
  | "toggleFavoriteGoods"
  | "favoriteGoods"
  | "goodsBatch"
  | "goodsGroup"
  | "cartList"
  | "cartAdd"
  | "cartUpdate"
  | "cartRemove"
> = {
  // ---------------------------------------------------------------- 商品
  async goodsList(q: GoodsQuery) {
    let list = allGoods().filter((g) => g.onSale);
    /*
     * 社区过滤是**邻里购物的第一约束**，不是排序偏好：
     * 隔壁区的生鲜店送不到我的自提点，它的商品出现在我的首页就是纯噪音。
     * 所以覆盖范围之外的直接**滤掉**，而不是排到后面。
     * serviceCommunityNos 为空 = 全域可售（平台自营、虚拟商品这类没有履约半径的），永远保留。
     */
    if (q.communityNo) {
      const cno = q.communityNo;
      list = list.filter((g) => reaches(g.merchant.merchantNo, cno));
      // 同在范围内时按距离近的在前 —— 近的能更早拿到货，也更可能是熟脸
      list = list.sort((a, b) => distanceOf(a) - distanceOf(b));
    } else if (q.regionCode) {
      /*
       * **粗定位兜底：按区筛。** 精确的结论压过它，所以只在没有 communityNo 时走这一支。
       *
       * mock 里也要真的筛 —— 恒不筛的话，端上「位置不明时看什么」这条分支
       * 在开发期与改造前长得一模一样（都是一屏全平台的货），改没改看不出来。
       */
      const prefix = q.regionCode;
      const inRegion = allCommunitySeeds()
        .filter((c) => (c.regionCode ?? "").startsWith(prefix))
        .map((c) => c.communityNo);
      list = list.filter((g) => inRegion.some((cno) => reaches(g.merchant.merchantNo, cno)));
    }
    if (q.merchantNo) list = list.filter((g) => g.merchant.merchantNo === q.merchantNo);
    if (q.type) list = list.filter((g) => g.type === q.type);
    if (q.categoryNo) list = list.filter((g) => g.categoryNo === q.categoryNo);
    if (q.keyword) {
      const k = q.keyword.trim().toLowerCase();
      list = list.filter(
        (g) => g.title.toLowerCase().includes(k) || g.subtitle.toLowerCase().includes(k),
      );
    }
    return delay(paginate(list, q.page, q.size));
  },

  async goodsDetail(goodsNo) {
    // mock 不判送达（deliverable 缺省 = 没判）；收藏读本地那份
    return delay({ ...toGoods(findGoodsSeed(goodsNo)), favorited: db.favoriteGoods.includes(goodsNo) });
  },

  async toggleFavoriteGoods(goodsNo) {
    findGoodsSeed(goodsNo);
    const i = db.favoriteGoods.indexOf(goodsNo);
    if (i >= 0) db.favoriteGoods.splice(i, 1);
    else db.favoriteGoods.unshift(goodsNo);
    persist();
    return delay({ favorited: i < 0 });
  },

  async favoriteGoods(page = 1, size = 20) {
    const list = db.favoriteGoods.map((no) => ({ ...toGoods(findGoodsSeed(no)), favorited: true }));
    return delay(paginate(list, page, size));
  },

  /** 拼团块：商品上配了团购价的才有；正在拼的团取这件货还没成的团 */
  async goodsGroup(goodsNo) {
    const g = toGoods(findGoodsSeed(goodsNo));
    if (!g.groupBuy) return delay(null);
    const open = db.groupSeeds
      .filter((s) => s.goodsNo === goodsNo)
      .map(buildGroupBuy)
      .filter((x) => x.status === "OPEN")
      .sort((a, b) => a.need - b.need)
      .slice(0, 3);
    return delay({
      goodsNo,
      activityNo: "PA-G1",
      groupPrice: open[0]?.groupPrice ?? g.groupBuy.price,
      minCount: g.groupBuy.minCount,
      groupHours: 24,
      openGroups: open,
    });
  },

  /** 集单块：mock 里只有生鲜类商品在集单（与原型 s26 同一件货的样子） */
  async goodsBatch(goodsNo) {
    const g = toGoods(findGoodsSeed(goodsNo));
    if (g.type !== "FRESH") return delay(null);
    const cut = new Date();
    cut.setHours(20, 0, 0, 0);
    if (Date.now() >= cut.getTime()) cut.setDate(cut.getDate() + 1);
    const pick = new Date(cut.getTime() + 86400_000);
    const pad = (n: number) => String(n).padStart(2, "0");
    return delay({
      activityNo: "PT-B1",
      activityName: "每日鲜果",
      batchPriceMinor: g.price,
      cutoffAt: cut.getTime(),
      pickupDate: `${pick.getFullYear()}-${pad(pick.getMonth() + 1)}-${pad(pick.getDate())}`,
      pickupFrom: "09:00",
      orderedQty: 86,
    });
  },

  // ---------------------------------------------------------------- 购物车
  async cartList() {
    db.cart = refreshCart();
    // 这是读操作，不落盘 —— 只是把标题按当前语言重算了一遍
    return delay([...db.cart]);
  },

  async cartAdd(goodsNo, skuNo, qty) {
    const seed = findGoodsSeed(goodsNo);
    const g = toGoods(seed);
    const sku = g.skus.find((s) => s.skuNo === skuNo);
    if (!sku) throw new Error("规格不存在");
    // 生鲜截单校验：截单后不可加购
    if (g.cutoffAt && Date.now() > g.cutoffAt) throw new Error("已过今日截单时间");
    const exist = db.cart.find((c) => c.skuNo === skuNo);
    if (exist) {
      exist.qty += qty;
    } else {
      db.cart.push({
        goodsNo,
        skuNo,
        title: g.title,
        cover: g.cover,
        spec: sku.spec,
        price: sku.price,
        qty,
        type: g.type,
        fulfillment: defaultFulfillment(g),
        // 商家：购物车与确认页要按它分段（一段 = 一笔子订单）。
        // 不带这两个字段的话，mock 下所有商品会聚成同一段，
        // 而那正是这个缺口此前藏了这么久的样子 —— 看起来「就是一单」
        merchantNo: g.merchant.merchantNo,
        merchantName: g.merchant.name,
      });
    }
    // 限购校验
    if (g.limitPerUser > 0) {
      const item = db.cart.find((c) => c.skuNo === skuNo)!;
      if (item.qty > g.limitPerUser) {
        item.qty = g.limitPerUser;
        throw new Error(`每人限购 ${g.limitPerUser} 件`);
      }
    }
    persist();
    db.cart = refreshCart();
    return delay([...db.cart]);
  },

  async cartUpdate(skuNo, qty) {
    const item = db.cart.find((c) => c.skuNo === skuNo);
    if (item) {
      if (qty <= 0) db.cart = db.cart.filter((c) => c.skuNo !== skuNo);
      else item.qty = qty;
    }
    persist();
    db.cart = refreshCart();
    return delay([...db.cart]);
  },

  async cartRemove(skuNos) {
    db.cart = db.cart.filter((c) => !skuNos.includes(c.skuNo));
    persist();
    db.cart = refreshCart();
    return delay([...db.cart]);
  },
};
