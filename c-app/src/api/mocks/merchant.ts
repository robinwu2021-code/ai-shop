// 商家与门店主页、评价 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { allGoods, db, delay, findGoodsSeed, persist, toGoods, toMerchant } from "@shared/mock/db";
import { defaultFulfillment } from "@shared/utils/goods";
import {
  aggregateFrequent,
  findOrder,
  reaches,
} from "./_shared";
import type { ShopApi } from "../contract";

export const merchantMock: Pick<ShopApi,
  "merchantList"
  | "visitedMerchants"
  | "merchantDetail"
  | "storeByCode"
  | "storeHome"
  | "frequentItems"
  | "promotedGoods"
  | "promotedMerchants"
  | "reorderFrom"
  | "toggleFavoriteStore"
  | "myStores"
  | "reviewList"
  | "toggleReviewLike"
> = {
  // ---------------------------------------------------------------- 商家
  async merchantList(q) {
    let list = db.merchantSeeds.map((m) => toMerchant(m.merchantNo));
    // 与 goodsList 同一条规矩：覆盖不到我这个社区的商家不该出现在列表里
    if (q?.communityNo) {
      const cno = q.communityNo;
      list = list
        .filter((m) => reaches(m.merchantNo, cno))
        .sort((a, b) => (a.distance ?? 0) - (b.distance ?? 0));
    }
    const k = q?.keyword?.trim().toLowerCase();
    if (k) {
      // 商家搜索匹配「名称 + 简介 + 标签」—— 只匹配名称的话，
      // 用户搜「家政」「理发」这类**经营内容**词会一条都搜不到
      list = list.filter(
        (m) =>
          m.name.toLowerCase().includes(k) ||
          m.desc.toLowerCase().includes(k) ||
          m.tags.some((t) => t.toLowerCase().includes(k)),
      );
    }
    return delay(list);
  },

  /** 我消费过的商家：从订单聚合。真实后端同样应由订单反查，不另存一张关系表 */
  async visitedMerchants() {
    const agg = new Map<string, { count: number; last: number }>();
    for (const o of db.orders) {
      if (o.status === "CANCELLED") continue;
      // 一单可能跨商家（拆单前的形态），按商家去重计数
      const merchants = new Set(
        o.items.map((it) => it.merchantNo || toGoods(findGoodsSeed(it.goodsNo)).merchant.merchantNo),
      );
      for (const mno of merchants) {
        const cur = agg.get(mno) ?? { count: 0, last: 0 };
        agg.set(mno, { count: cur.count + 1, last: Math.max(cur.last, o.createdAt) });
      }
    }
    const list = [...agg.entries()]
      .map(([mno, v]) => ({ ...toMerchant(mno), orderCount: v.count, lastOrderAt: v.last }))
      .sort((a, b) => b.lastOrderAt - a.lastOrderAt);
    return delay(list);
  },

  async merchantDetail(merchantNo) {
    return delay(toMerchant(merchantNo));
  },

  // ---------------------------------------------------------------- 门店主页
  /*
   * 扫码进店。mock 里从码值反查商家：约定 `shop_<merchantNo>_<x>`，
   * 与 mock 店铺码数据同一套字面量。**认不出来就报错，不静默回落到某一家** ——
   * 静默回落会让「码印错了」这件事在演示里永远不出现，而那正是它最该出现的地方。
   */
  async storeByCode(storeCode) {
    const m = /^shop_([A-Za-z0-9-]+)_/.exec(storeCode ?? "");
    if (!m) throw new Error(`店铺码不存在：${storeCode}`);
    // 走 QR 口径：扫码进店要写归因，与真后端一致
    return this.storeHome(m[1]!, "QR");
  },

  async storeHome(merchantNo, from) {
    const merchant = toMerchant(merchantNo);
    // 扫码/分享进店即写归因：这决定后续订单的 trafficSource 与商家费率档（ADR-004 §6）。
    // **最近一次进店覆盖前一次**，不设窗口 —— 用户此刻在谁家买，就算谁带来的
    if (from === "QR" || from === "SHARE") db.user.merchantNo = merchantNo;
    const onSale = allGoods().filter((g) => g.onSale && g.merchant.merchantNo === merchantNo);
    /*
     * 本店货架。mock 里按在售商品的类目现算 —— 真后端那边还会叠一层店主排的顺序与
     * 改过的显示名，但 mock 没有货架表，硬造一份会让「店主改名」这件事在 mock 上
     * 看着已经生效，而真库里其实没配。这里只保证**形状**对，不假装数据也对。
     */
    const catName = (no: string) =>
      db.categories.find((c) => c.categoryNo === no)?.name ?? "";
    const counted = new Map<string, number>();
    for (const g of onSale) {
      if (g.categoryNo) counted.set(g.categoryNo, (counted.get(g.categoryNo) ?? 0) + 1);
    }
    return delay({
      merchant,
      store: { ...db.store },
      goods: onSale,
      categories: [...counted.entries()]
        .map(([categoryNo, count]) => ({ categoryNo, name: catName(categoryNo), count }))
        .filter((c) => !!c.name),
      favorited: db.favoriteStores.includes(merchantNo),
      /*
       * 停业标志。mock 里由商家种子的 status 推出 —— **不能恒为 false**：
       * 恒 false 的话「已停业」这条分支在 mock 下永远走不到，
       * 而它恰恰是扫码老客最需要看见的那一条。
       */
      closed: db.merchantSeeds.find((m) => m.merchantNo === merchantNo)?.closed === true,
    });
  },

  async frequentItems(merchantNo) {
    const rows = aggregateFrequent((goodsNo) => findGoodsSeed(goodsNo).merchantNo === merchantNo);
    if (rows.length) return delay(rows);
    // 未登录/没买过时降级为店铺热销 —— 空着一片「我买过的」比没有这个模块更差
    return delay(
      allGoods()
        .filter((g) => g.onSale && g.merchant.merchantNo === merchantNo)
        .slice(0, 6)
        .map((g) => ({
          goodsNo: g.goodsNo,
          skuNo: g.skus[0]!.skuNo,
          title: g.title,
          cover: g.cover,
          spec: g.skus[0]!.spec,
          price: g.skus[0]!.price,
          lastPrice: g.skus[0]!.price,
          times: 0,
          lastAt: 0,
          invalid: (g.skus[0]!.stock ?? 0) <= 0,
        })),
    );
  },

  async promotedGoods(q) {
    // 一期没有运营后台，用「本社区可售 + 销量高」兜底。
    // 刻意**不与首页主列表同序**：主列表按距离，这里按销量，两处才不是同一个列表。
    const list = allGoods()
      .filter((g) => g.onSale && reaches(g.merchant.merchantNo, q?.communityNo))
      .sort((a, b) => b.sales - a.sales)
      .slice(0, q?.size ?? 6);
    return delay(list);
  },

  async promotedMerchants(q) {
    // 一期没有运营后台：用「本社区可达 + 入驻晚」兜底 —— 正好对上这个位子的用途，
    // 新店在按销量/评分排的列表里永远垫底，需要一个不看历史成绩的位置。
    const list = db.merchantSeeds
      .filter((m) => reaches(m.merchantNo, q?.communityNo))
      .sort((a, b) => b.joinedAt - a.joinedAt)
      .slice(0, q?.size ?? 4)
      .map((m) => toMerchant(m.merchantNo));
    return delay(list);
  },

  async reorderFrom(orderNo) {
    const o = findOrder(orderNo);
    const dropped: string[] = [];
    const priceUp: string[] = [];
    let added = 0;

    for (const it of o.items) {
      if (it.isGift) continue; // 赠品由促销规则实时算，不能当普通商品加回去
      const g = toGoods(findGoodsSeed(it.goodsNo));
      const sku = g.skus.find((k) => k.skuNo === it.skuNo);
      // 失效的**显式回报**，不静默丢 —— 少加了东西用户到付款才发现，是投诉源头
      if (!g.onSale || !sku || sku.stock <= 0) {
        dropped.push(g.title);
        continue;
      }
      if (sku.price > it.price) priceUp.push(g.title);
      const exist = db.cart.find((c) => c.skuNo === it.skuNo);
      if (exist) exist.qty += it.qty;
      else {
        db.cart.push({
          goodsNo: g.goodsNo,
          skuNo: sku.skuNo,
          title: g.title,
          cover: g.cover,
          spec: sku.spec,
          price: sku.price,
          qty: it.qty,
          type: g.type,
          fulfillment: defaultFulfillment(g),
          merchantNo: g.merchant.merchantNo,
          merchantName: g.merchant.name,
        });
      }
      added += 1;
    }
    persist();
    return delay({ added, dropped, priceUp });
  },

  async toggleFavoriteStore(merchantNo) {
    const i = db.favoriteStores.indexOf(merchantNo);
    if (i >= 0) db.favoriteStores.splice(i, 1);
    else db.favoriteStores.unshift(merchantNo);
    persist();
    return delay(i < 0);
  },

  async myStores() {
    return delay(db.favoriteStores.map(toMerchant));
  },

  // ---------------------------------------------------------------- 评价
  async reviewList(q) {
    let list = [...db.reviews];
    if (q.goodsNo) list = list.filter((r) => r.goodsNo === q.goodsNo);
    if (q.merchantNo) list = list.filter((r) => r.merchantNo === q.merchantNo);
    // 有图的、点赞多的排前面 —— 对后来的买家更有参考价值
    list.sort(
      (a, b) =>
        (b.images.length ? 1 : 0) - (a.images.length ? 1 : 0) ||
        b.likeCount - a.likeCount ||
        b.createdAt - a.createdAt,
    );
    return delay(list);
  },

  async toggleReviewLike(reviewNo) {
    const r = db.reviews.find((x) => x.reviewNo === reviewNo);
    if (!r) throw new Error("评价不存在");
    r.liked = !r.liked;
    r.likeCount = Math.max(0, r.likeCount + (r.liked ? 1 : -1));
    persist();
    return delay({ ...r });
  },
};
