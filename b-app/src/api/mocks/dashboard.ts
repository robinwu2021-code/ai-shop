// 工作台：统计与待办、增值包、跨店总览 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, persist } from "@shared/mock/db";
import { ApiError } from "@shared/net/http-client";
import type { Order } from "@shared/types";
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
