// 工作台：统计与待办、增值包、跨店总览 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, persist } from "@shared/mock/db";
import { ApiError } from "@shared/net/http-client";
import type {
  DailyReport,
  GoodsRank,
  MonthlyReport,
  Order,
} from "@shared/types";
import { currentCurrency } from "@shared/utils/money";
import {
  MOCK_PLAN_KEY,
  MOCK_TRIAL_KEY,
  belongsToMerchant,
  crossStoreOrders,
  crossStoreStores,
  hashPick,
  minePlan,
  myGoods,
  requireCrossStoreStats,
  scopedToStore,
  storeOfOrder,
  storeRating,
  sumPayable,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const dashboardMock: Pick<MerchantApi,
  "mTodo"
  | "mStats"
  | "mDailyReport"
  | "mGoodsRank"
  | "mMonthlyReport"
  | "mMyPlan"
  | "mStartTrial"
  | "mCrossStoreOverview"
  | "mCrossStoreCompare"
> = {
  // ---------------------------------------------------------------- 工作台
  async mTodo() {
    const merchantNo = db.merchant.merchantNo;
    // 待办同样按当前门店（后端 BizDashboardController#todo 走 currentStoreScope）
    const mine = merchantNo
      ? scopedToStore(db.orders.filter((o) => belongsToMerchant(o, merchantNo))) : [];
    const pickupNo = db.merchant.pickupNo;
    const atMyPoint = db.merchant.isPickupPoint
      ? db.orders.filter((o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo))
      : [];
    return delay({
      toShip: mine.filter((o) => o.fulfillment === "EXPRESS" && o.status === "PAID").length,
      toDeliver: mine.filter((o) => o.fulfillment === "MERCHANT_DELIVERY" && o.status === "PAID").length,
      // 待备货按**我的单**算（mine），不是按我的自提点（atMyPoint）——
      // 买家常常选别家的点，两个数因此不相等。后端也是这个口径
      toStock: mine.filter((o) => o.fulfillment === "STORE_PICKUP" && o.status === "PAID").length,
      toVerify: atMyPoint.filter((o) => o.status === "FULFILLING").length,
      toPick: atMyPoint.filter((o) => o.status === "PAID").length,
      afterSale: mine.filter((o) => o.afterSale?.status === "APPLIED").length,
      toReply: db.reviews.filter((r) => r.merchantNo === merchantNo && !r.reply).length,
      quotable: 0, // 求团报价在 M3 批次交付
    });
  },

  /**
   * 近几日（R1）。mock 里**没有日结**，所以这里直接按订单逐日算 ——
   * 与真后端的分界（今天现算、T-1 读汇总）在结果上等价，
   * 但 `complete` 恒为 true：mock 没有「日结没跑到」这个状态。
   * 端上的缺口提示要靠真后端验，mock 上看不出来。
   */
  async mDailyReport(days?: number) {
    const n = [7, 14, 30].includes(days ?? 7) ? (days ?? 7) : 7;
    const merchantNo = db.merchant.merchantNo;
    const mine = scopedToStore(db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED",
    ));
    const dayKey = (ms: number) => new Date(ms).toISOString().slice(0, 10);
    const bucket = (from: number, to: number) => mine.filter(
      (o) => o.createdAt >= from && o.createdAt < to,
    );
    const dayMs = 86400000;
    const todayStart = new Date().setHours(0, 0, 0, 0);
    const rows = [];
    for (let i = 0; i < n; i++) {
      const start = todayStart - i * dayMs;
      const list = bucket(start, start + dayMs);
      rows.push({
        date: dayKey(start),
        orders: list.length,
        gmvMinor: list.reduce((s, o) => s + o.amount.payableMinor, 0),
        refundOrders: 0,
        refundMinor: 0,
        complete: true,
      });
    }
    const prev = bucket(todayStart - (2 * n - 1) * dayMs, todayStart - (n - 1) * dayMs);
    return {
      days: n,
      // mock 只有人民币；真后端从子单上带出来
      currency: "CNY",
      totalOrders: rows.reduce((s, r) => s + r.orders, 0),
      totalGmvMinor: rows.reduce((s, r) => s + r.gmvMinor, 0),
      prevOrders: prev.length,
      prevGmvMinor: prev.reduce((s, o) => s + o.amount.payableMinor, 0),
      statsThrough: dayKey(todayStart - dayMs),
      rows,
    } as DailyReport;
  },

  /**
   * 商品销售榜（R3）。mock 从订单行直接算 —— 没有日结那一层。
   *
   * ⚠️ 与真后端一样**赠品不进 qty**：混进去的话「送出去 100 件」会被读成
   * 「卖了 100 件」。mock 里若没有赠品行，这条分支在 mock 上看不出来，要靠真后端验。
   */
  async mGoodsRank(q?: { days?: number; orderBy?: string; limit?: number }) {
    const days = [7, 14, 30].includes(q?.days ?? 30) ? (q?.days ?? 30) : 30;
    const merchantNo = db.merchant.merchantNo;
    const dayMs = 86400000;
    const todayStart = new Date().setHours(0, 0, 0, 0);
    // 不含今天 —— 与真后端同一口径
    const to = todayStart;
    const from = todayStart - (days - 1) * dayMs;
    const mine = scopedToStore(db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED"
        && o.createdAt >= from && o.createdAt < to,
    ));
    const acc = new Map<string, { title: string; spec: string; qty: number; amountMinor: number; giftQty: number }>();
    for (const o of mine) {
      for (const it of o.items ?? []) {
        const k = it.goodsNo ?? it.skuNo ?? "";
        if (!k) continue;
        const cur = acc.get(k) ?? { title: it.title ?? "", spec: it.spec ?? "", qty: 0, amountMinor: 0, giftQty: 0 };
        if (it.isGift) cur.giftQty += it.qty ?? 0;
        else {
          cur.qty += it.qty ?? 0;
          cur.amountMinor += (it.price ?? 0) * (it.qty ?? 0);
        }
        acc.set(k, cur);
      }
    }
    const byAmount = (q?.orderBy ?? "qty") === "amount";
    const rows = [...acc.entries()]
      .map(([goodsNo, v]) => ({ goodsNo, title: v.title, spec: v.spec, qty: v.qty, amountMinor: v.amountMinor, giftQty: v.giftQty }))
      .sort((a, b) => (byAmount ? b.amountMinor - a.amountMinor : b.qty - a.qty))
      .slice(0, Math.min(Math.max(q?.limit ?? 10, 1), 50));
    return {
      days,
      orderBy: byAmount ? "amount" : "qty",
      currency: "CNY",
      statsThrough: new Date(todayStart - dayMs).toISOString().slice(0, 10),
      rows,
    } as GoodsRank;
  },

  /**
   * 按月营收（R2）。mock 从订单直接按月分组 —— 没有日结那一层。
   *
   * ⚠️ 真后端的「本月那一行多半不全」（日结只算到 T-1）在 mock 上**看不出来**：
   * 这里把今天的单也算进了本月。那条提示要靠真后端验。
   */
  async mMonthlyReport(months?: number) {
    const n = Math.min(Math.max(months ?? 6, 1), 24);
    const merchantNo = db.merchant.merchantNo;
    const mine = scopedToStore(db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED",
    ));
    const now = new Date();
    const keyOf = (ms: number) => {
      const d = new Date(ms);
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
    };
    const wanted: string[] = [];
    for (let i = 0; i < n; i++) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      wanted.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`);
    }
    const rows = wanted.map((month) => {
      const list = mine.filter((o) => keyOf(o.createdAt) === month);
      const gmv = list.reduce((s, o) => s + o.amount.payableMinor, 0);
      return {
        month,
        orders: list.length,
        gmvMinor: gmv,
        refundOrders: 0,
        refundMinor: 0,
        // mock 不算费率，只把结构摆出来；真后端从子单上带
        commissionMinor: 0,
        serviceFeeMinor: 0,
        freightIncomeMinor: 0,
        netMinor: gmv,
      };
    });
    return {
      months: n,
      currency: "CNY",
      statsThrough: new Date(Date.now() - 86400000).toISOString().slice(0, 10),
      totalOrders: rows.reduce((s, r) => s + r.orders, 0),
      totalNetMinor: rows.reduce((s, r) => s + r.netMinor, 0),
      rows,
    } as MonthlyReport;
  },

  async mStats() {
    const merchantNo = db.merchant.merchantNo;
    // ★ 按当前门店，不是名下全部 —— 与后端 BizDashboardController#stats 同一口径
    //（那里的注释原话：「否则切门店时这几个数字不会变」）
    const mine = scopedToStore(db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && o.status !== "CANCELLED",
    ));
    const dayStart = new Date().setHours(0, 0, 0, 0);
    const today = mine.filter((o) => o.createdAt >= dayStart);
    const sum = (list: Order[]) => list.reduce((s, o) => s + o.amount.payableMinor, 0);
    const rs = db.reviews.filter((r) => r.merchantNo === merchantNo);
    const owned = mine.filter((o) => o.trafficSource === "MERCHANT_OWNED").length;
    return delay({
      todayOrders: today.length,
      todayGmvMinor: sum(today),
      monthOrders: mine.length,
      monthGmvMinor: sum(mine),
      currency: currentCurrency(),
      rating: rs.length ? Number((rs.reduce((s, r) => s + r.rating, 0) / rs.length).toFixed(1)) : 0,
      ratingCount: rs.length,
      ownedTrafficRate: mine.length ? owned / mine.length : 0,
      // 近 7 天到访（§6）—— mock 给个真实点的数，别用 0（0 与「没人来」长得一样）
      visitPv7d: 48,
      visitUv7d: 31,
    });
  },

  // ------------------------------------------------ 我的增值包（增值包 P4）
  async mMyPlan() {
    return delay(minePlan());
  },

  async mStartTrial() {
    const plan = minePlan();
    if (!plan.trialTier) {
      // 与后端同一个口径：三种拒因（已用过 / 已经是付费档 / 没配试用）合成一个
      throw new ApiError(10400, "当前不能开通试用");
    }
    /*
     * **真落库**：写进本地存储的档位开关 + 放开额度，重开小程序读回来还是试用中。
     * 只在内存里改的话，页面上「试用已开通」而下一次进来又回到 FREE ——
     * 而那正是这个功能最需要被看到的一段（试用期内他会反复进来看还剩几天）。
     */
    uni.setStorageSync(MOCK_PLAN_KEY, plan.trialTier);
    const tier = plan.tiers.find((t: { planCode: string }) => t.planCode === plan.trialTier);
    db.storeQuota = Math.max(db.storeQuota, tier?.storeQuota ?? 1);
    uni.setStorageSync(MOCK_TRIAL_KEY, Date.now());
    persist();
    return delay(minePlan());
  },

  // ------------------------------------------------ 跨店总览与对比（增值包 P2）
  async mCrossStoreOverview() {
    requireCrossStoreStats();
    const stores = crossStoreStores();
    const mine = crossStoreOrders();
    const dayStart = new Date().setHours(0, 0, 0, 0);

    return delay({
      currency: currentCurrency(),
      stores: stores.map((s) => {
        const rows = mine.filter((o) => storeOfOrder(o, stores) === s.storeNo);
        const today = rows.filter((o) => o.createdAt >= dayStart);
        const paid = (f: string) => rows.filter((o) => o.fulfillment === f && o.status === "PAID").length;
        return {
          storeNo: s.storeNo,
          storeName: s.name,
          isDefault: s.isDefault,
          status: s.status,
          todayOrders: today.length,
          todayGmvMinor: sumPayable(today),
          monthOrders: rows.length,
          monthGmvMinor: sumPayable(rows),
          toShip: paid("EXPRESS"),
          toDeliver: paid("MERCHANT_DELIVERY"),
          toStock: paid("STORE_PICKUP"),
        };
      }),
    });
  },

  async mCrossStoreCompare(days) {
    requireCrossStoreStats();
    // 与后端同一条夹取：端上传 0 或 99999 不该让整页报错
    const window = Math.min(Math.max(days ?? 30, 1), 365);
    const stores = crossStoreStores();
    const since = Date.now() - window * 86_400_000;
    const mine = crossStoreOrders().filter((o) => o.createdAt >= since);
    const rs = db.reviews.filter((r) => r.merchantNo === db.merchant.merchantNo);
    // 缺货：可用量 ≤ 0 的 SKU。mock 没有店级库存表，按同一套散列分给各店
    const oosSkus = myGoods().flatMap((g) =>
      g.skus.filter((k) => (k.stock ?? 0) <= 0).map((k) => k.skuNo),
    );

    return delay({
      days: window,
      currency: currentCurrency(),
      /*
       * 主体整体评分：与 mStats 用**同一个算法**（同一批评价、同一个口径）。
       * 每家店自己的分在下面每行的 rating 上（V155 起，评价归门店）。
       */
      rating: rs.length ? Number((rs.reduce((s, r) => s + r.rating, 0) / rs.length).toFixed(1)) : 0,
      ratingCount: rs.length,
      stores: stores.map((s) => {
        const rows = mine.filter((o) => storeOfOrder(o, stores) === s.storeNo);
        const perBuyer = new Map<string, number>();
        for (const o of rows) {
          const who = o.buyerNickname || o.receiver?.name || o.orderNo;
          perBuyer.set(who, (perBuyer.get(who) ?? 0) + 1);
        }
        const buyers = perBuyer.size;
        const repeatBuyers = [...perBuyer.values()].filter((n) => n >= 2).length;
        return {
          storeNo: s.storeNo,
          storeName: s.name,
          isDefault: s.isDefault,
          status: s.status,
          orders: rows.length,
          gmvMinor: sumPayable(rows),
          buyers,
          repeatBuyers,
          /*
           * 门店评分（V155）。mock 里的评价没有 store_no，所以**按订单反推**：
           * 这家店的单对应的那些评价。真后端读的是 rvw_review.store_no ——
           * 两边算法不同但**语义相同**，而这里刻意不去伪造一个 store_no：
           * 伪造的话，mock 与真库对「老评价没有门店归属」这件事的表现会不一样。
           */
          ...storeRating(rows, rs),
          // 分母为 0 时是 0，不是除零、不是 null —— 还没开张的店显示 0%
          repeatRate: buyers ? repeatBuyers / buyers : 0,
          outOfStockSkus: oosSkus.filter((no) => hashPick(no, stores) === s.storeNo).length,
        };
      }),
    });
  },
};
