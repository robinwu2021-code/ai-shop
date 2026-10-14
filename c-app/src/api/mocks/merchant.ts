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
import type { Merchant, StoreCard, StoreVisitSource } from "@shared/types";

/*
 * mock 的门店：**一个商家种子 = 一家同名门店**，门店号 `ST` + 商家号。
 * 真后端一个主体可以有几家店；mock 的种子只到主体这一层，多店的样子在原型里看。
 * 老链接带主体号（M…）进来，落到这家店 —— 与服务端的前缀分派同一条规则。
 */
const STORE_PREFIX = "ST";
const toStoreNo = (merchantNo: string) => STORE_PREFIX + merchantNo;
const toMerchantNo = (no: string) => (no.startsWith(STORE_PREFIX) ? no.slice(STORE_PREFIX.length) : no);
/** 只逛过的门店在「我的店」里留几天。与后端 shop.mp.my-store.view-keep-days 同值 */
const VIEW_KEEP_MS = 30 * 86_400_000;

/** 进店记录：只在内存里 —— 刷新即清，mock 里「我的店」从空开始正是要看的那一屏 */
const storeViews = new Map<string, { firstSource: StoreVisitSource; lastAt: number }>();

function toStoreCard(m: Merchant): StoreCard {
  return {
    storeNo: toStoreNo(m.merchantNo),
    storeName: m.name,
    entityNo: m.merchantNo,
    logo: m.logo ?? "",
    status: "ACTIVE",
    openNow: null,
    openHours: m.openHours ?? "",
    address: m.address ?? "",
    // mock 的距离是「离 CM001 多远」，不是离买家 —— 够看排序，不够看真实数字
    distanceM: m.distance || null,
    rating: m.rating,
    ratingCount: m.ratingCount,
  };
}

/**
 * mock 的问答库。**种一条已回答的**：端上那段「大家还问」在 mock 下要看得见，
 * 否则改完版式只能靠读代码判断它有没有渲染。
 */
const mockQuestions: Array<{
  questionNo: string; goodsNo: string; content: string; status: string;
  answer?: string; createdAt?: string;
}> = [
  {
    questionNo: "QA-SEED-1", goodsNo: "G1001", content: "这个甜吗？",
    status: "ANSWERED", answer: "很甜，糖度 16 以上。", createdAt: "2026-09-01T00:00:00Z",
  },
];

export const merchantMock: Pick<ShopApi,
  "merchantList"
  | "visitedMerchants"
  | "merchantDetail"
  | "storeByCode"
  | "storeHome"
  | "storeGoods"
  | "storeAcode"
  | "frequentItems"
  | "promotedGoods"
  | "promotedMerchants"
  | "reorderFrom"
  | "toggleFavoriteStore"
  | "favoriteStores"
  | "myStores"
  | "storeNearby"
  | "storeEnter"
  | "reviewList"
  | "questionList"
  | "myFission"
  | "bootstrapConfig"
  | "merchantAcode"
  | "askQuestion"
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

  async storeHome(no, from) {
    // 门店号与主体号都认（与服务端前缀分派同一条规则）
    const merchantNo = toMerchantNo(no);
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
      portal: {
        storeNo: toStoreNo(merchantNo),
        storeName: merchant.name,
        status: db.merchantSeeds.find((m) => m.merchantNo === merchantNo)?.closed === true ? "READONLY" : "ACTIVE",
        isDefault: true,
        openNow: null,
        rating: merchant.rating,
        ratingCount: merchant.ratingCount,
        distanceM: merchant.distance || null,
      },
      // mock 一个主体只有一家店，没有「隔壁店」可给
      sibling: null,
    });
  },

  async storeGoods(no, q) {
    const merchantNo = toMerchantNo(no);
    const page = q?.page ?? 1;
    const size = q?.size ?? 20;
    const k = q?.keyword?.trim().toLowerCase();
    const all = allGoods().filter(
      (g) =>
        g.onSale &&
        g.merchant.merchantNo === merchantNo &&
        (!q?.categoryNo || g.categoryNo === q.categoryNo) &&
        (!k || g.title.toLowerCase().includes(k)),
    );
    return delay({ records: all.slice((page - 1) * size, page * size), total: all.length, page, size });
  },

  /** 门店码：与 merchantAcode 同一个理由给 null（mock 没有 wxacode 通道） */
  async storeAcode(no) {
    const merchantNo = toMerchantNo(no);
    return delay({ storeNo: toStoreNo(merchantNo), storeName: toMerchant(merchantNo).name, imageBase64: null });
  },

  async frequentItems(no) {
    const merchantNo = toMerchantNo(no);
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
    return delay({ favorited: i < 0 });
  },

  async favoriteStores() {
    return delay(db.favoriteStores.map(toMerchant));
  },

  async myStores() {
    const since = Date.now() - VIEW_KEEP_MS;
    // 成交口径：取消的单不算，与后端 OrdSubOrder.PAID 同义
    const bought = new Map<string, { count: number; last: number }>();
    for (const o of db.orders) {
      if (o.status === "CANCELLED" || o.status === "WAIT_PAY") continue;
      const mnos = new Set(
        o.items.map((it) => it.merchantNo || toGoods(findGoodsSeed(it.goodsNo)).merchant.merchantNo),
      );
      for (const mno of mnos) {
        const cur = bought.get(mno) ?? { count: 0, last: 0 };
        bought.set(mno, { count: cur.count + 1, last: Math.max(cur.last, o.createdAt) });
      }
    }
    const mnos = new Set(bought.keys());
    for (const [storeNo, v] of storeViews) if (v.lastAt >= since) mnos.add(toMerchantNo(storeNo));
    const list = [...mnos].map((mno) => {
      const b = bought.get(mno);
      const v = storeViews.get(toStoreNo(mno));
      return {
        ...toStoreCard(toMerchant(mno)),
        relation: {
          orderCount: b?.count ?? 0,
          lastOrderAt: b?.last ?? null,
          lastViewAt: v?.lastAt ?? null,
          firstSource: v?.firstSource ?? null,
        },
      };
    });
    const touch = (c: StoreCard) =>
      Math.max(c.relation?.lastOrderAt ?? 0, c.relation?.lastViewAt ?? 0);
    return delay(list.sort((a, b) => touch(b) - touch(a)));
  },

  async storeNearby(q) {
    const mine = new Set((await this.myStores()).map((c) => c.storeNo));
    const page = q?.page ?? 1;
    const size = q?.size ?? 20;
    const all = (await this.merchantList({ communityNo: q?.communityNo, keyword: q?.keyword }))
      .map(toStoreCard)
      .filter((c) => !mine.has(c.storeNo))
      // 与后端同一条：有距离的按距离升序，没距离的排后面
      .sort((a, b) => (a.distanceM ?? Infinity) - (b.distanceM ?? Infinity));
    return delay({ records: all.slice((page - 1) * size, page * size), total: all.length, page, size });
  },

  async storeEnter(no, req) {
    const storeNo = toStoreNo(toMerchantNo(no));
    toMerchant(toMerchantNo(no)); // 不存在就抛 —— 与后端 404 同一效果
    const prev = storeViews.get(storeNo);
    storeViews.set(storeNo, {
      // 首次来源只定一次
      firstSource: prev?.firstSource ?? req?.source ?? "LIST",
      lastAt: Date.now(),
    });
    return delay(undefined);
  },

  // ---------------------------------------------------------------- 评价
  async reviewList(q) {
    let list = [...db.reviews];
    if (q.goodsNo) list = list.filter((r) => r.goodsNo === q.goodsNo);
    if (q.merchantNo) list = list.filter((r) => r.merchantNo === q.merchantNo);
    // mock 的评价没有门店号：一个主体一家店，按门店看等于按它的主体看
    if (q.storeNo) list = list.filter((r) => r.merchantNo === toMerchantNo(q.storeNo!));
    /*
     * 筛选与分页**也在 mock 里做一遍**（§3.3）：不做的话，端上「切到差评」
     * 在 mock 下看起来什么都没变，而那正是要验的那一步。
     * 不认识的筛选词按全部处理，与后端同一条取舍。
     */
    if (q.filter === "IMAGE") list = list.filter((r) => r.images.length > 0);
    if (q.filter === "GOOD") list = list.filter((r) => r.rating >= 4);
    if (q.filter === "BAD") list = list.filter((r) => r.rating <= 2);
    // 有图的、点赞多的排前面 —— 对后来的买家更有参考价值
    list.sort(
      (a, b) =>
        (b.images.length ? 1 : 0) - (a.images.length ? 1 : 0) ||
        b.likeCount - a.likeCount ||
        b.createdAt - a.createdAt,
    );
    const size = Math.max(1, Math.min(q.size ?? 20, 50));
    const from = Math.max(0, ((q.page ?? 1) - 1) * size);
    return delay(list.slice(from, from + size));
  },

  /**
   * 商品问答。mock 自己存一份 —— 「提问 → 待回答 → 答完才出现」这条链路
   * 在 mock 下也要走得通，否则端上那段空态永远看不到。
   */
  /**
   * 邀请有礼。**mock 里默认有一场活动在跑** —— 要验的正是「有活动时那一屏长什么样」，
   * 而线上此刻一场都没建（`mkt_fission_campaign` 0 行）。
   * 额度取方案 §7.2 建议的那套：双方各 1 张「满 60 减 8」。
   */
  /**
   * 冷启动配置。**mock 里入驻开关默认开** —— 与后端默认一致（2026-09-28 拍板），
   * 要验的正是「开着时那一屏长什么样」。
   */
  async bootstrapConfig() {
    return delay({
      defaultSkin: "fresh",
      features: { "merchant.apply.mp-visible": true, points: false },
      minAppVer: "1.0.0",
      serviceHours: "09:00-21:00",
      /*
       * 商家版 App 的下载地址。**安卓给真地址、iOS 留空** —— 与线上实况一致：
       * iOS 版还在苹果审核队列里。空的那一档端上不显示，
       * 而「iOS 那一栏会不会显示」正是这一段要验的（[[default-off-is-the-untested-half]]）。
       */
      merchantApp: {
        // 带版本号的真实文件名 —— 线上清单下发的就是这个形状（不是 latest 软链，
        // 固定文件名会被浏览器与 CDN 缓存着当新包给出去）
        android: "https://www.hxmall.top/dl/hxmall-merchant-0.5.21.apk",
        ios: "",
        // 后端从版本清单读的。**给非空值**：空串那一支是「后端也没读到」，
        // 而 mock 要验的是正常那一屏长什么样
        androidVersion: "0.5.21",
      },
    });
  },

  async myFission() {
    return delay({
      fissionNo: "FS-MOCK-1",
      name: "邀请有礼",
      inviterCount: 1,
      inviteeCount: 1,
      couponTitle: "满 60 减 8",
      faceMinor: 800,
      thresholdMinor: 6000,
      myInvited: 2,
      // 刻意与 myInvited 不等：奖励按首单发，两个数不一样才是常态，
      // 相等的话端上那句「其中 N 人已下单」永远看不出差别
      myConverted: 1,
    });
  },

  /**
   * 店铺码。**mock 给 null** —— 它走的是微信的 wxacode 通道，mock 里没有，
   * 编一张假码图只会让「通道没开时海报长什么样」这条分支永远测不到，
   * 而那恰恰是常态（通道未开启时后端就返回 null）。
   */
  async merchantAcode(merchantNo) {
    return delay({ merchantNo, imageBase64: null });
  },

  async questionList(goodsNo, limit) {
    const list = mockQuestions
      .filter((x) => x.goodsNo === goodsNo && x.status === "ANSWERED")
      .slice(0, limit ?? 3);
    return delay(list);
  },

  async askQuestion(goodsNo, content) {
    const q = {
      questionNo: `QA${Date.now()}`,
      goodsNo,
      content,
      status: "PENDING",
      createdAt: new Date().toISOString(),
    };
    mockQuestions.unshift(q);
    return delay(q);
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
