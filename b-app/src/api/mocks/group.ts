// 团购与求团报价 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { allCommunitySeeds, buildGroupBuy, db, delay, findGoodsSeed, nextNo, persist, toGoods, toGroupRequest } from "@shared/mock/db";
import { MERCHANT_LOGO_FALLBACK } from "@shared/utils/constants";
import {
  requireMerchant,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const groupMock: Pick<MerchantApi,
  "mGroupList"
  | "mCreateGroup"
  | "mRequestList"
  | "mQuote"
> = {
  // ---------------------------------------------------------------- 团购与报价
  async mGroupList() {
    const merchantNo = db.merchant.merchantNo;
    return delay(
      db.groupSeeds
        .map(buildGroupBuy)
        .filter((g) => g.merchant.merchantNo === merchantNo),
    );
  },

  async mCreateGroup(goodsNo) {
    requireMerchant();
    const goods = toGoods(findGoodsSeed(goodsNo));
    // 商品没配 {起团人数, 团购价} 就不能开团 —— 团价从哪来？（需求 §五之四）
    if (!goods.groupBuy) throw new Error("该商品未配置团购价，先在商品里配置");
    // 截止时间取「团有效期」与「当日截单」的更早者：截单已过就只能开出一个死团
    // （倒计时直接 00:00:00），不如当场说清楚
    if (goods.cutoffAt && goods.cutoffAt <= Date.now()) {
      throw new Error("今日已截单，明天再开团");
    }
    const seed = {
      groupNo: nextNo("GB"),
      goodsNo,
      // 成团单位是自提点：拼的是一车送到一个点的成本，跨点凑人对成本无帮助
      pickupNo: db.merchant.pickupNo ?? allCommunitySeeds()[0]!.pickups[0]!.pickupNo,
      initiatorNickname: db.merchant.name || "商家",
      initiatorAvatar: db.merchant.logo || MERCHANT_LOGO_FALLBACK,
      createdAt: Date.now(),
      members: [],
      joined: false,
    };
    db.groupSeeds.unshift(seed as (typeof db.groupSeeds)[number]);
    persist();
    return delay(buildGroupBuy(seed as (typeof db.groupSeeds)[number]));
  },

  async mRequestList() {
    // 商家看得到所有开放中的需求单。初期靠运营人肉指派（P-8.2.2），
    // 这里先全量放出，商家自己挑 —— 需求少的时候人肉和自助没差别
    return delay(db.requests.filter((r) => r.status === "COLLECTING").map(toGroupRequest));
  },

  async mQuote(requestNo, payload) {
    const merchantNo = requireMerchant();
    const seed = db.requests.find((r) => r.requestNo === requestNo);
    if (!seed) throw new Error("需求单不存在");
    if (seed.status !== "COLLECTING") throw new Error("该需求单已不接受报价");

    const exist = seed.quotes.find((q) => q.merchantNo === merchantNo);
    if (exist) {
      // 选定后锁价：加价在技术上做不到，不靠事前审核（ADR-003）
      if (exist.locked) throw new Error("已被选定并锁价，不能再改");
      // 改价留痕。**只公示涨价** —— 降价对邻居是好事，公示反而劝退商家降价
      if (payload.priceMinor > exist.priceMinor) {
        exist.revisions.push({ priceMinor: exist.priceMinor, at: Date.now() });
      }
      exist.priceMinor = payload.priceMinor;
      exist.minCount = payload.minCount;
      exist.desc = payload.desc;
    } else {
      seed.quotes.push({
        quoteNo: nextNo("QT"),
        merchantNo,
        priceMinor: payload.priceMinor,
        minCount: payload.minCount,
        desc: payload.desc,
        validUntil: Date.now() + 3 * 86400_000,
        createdAt: Date.now(),
        chosen: false,
        revisions: [],
        locked: false,
      });
    }
    persist();
    // **返回这条报价**，不是整张需求单：后端 /biz/group-request/{no}/quote 发的是 QuoteVO。
    // 此前返回需求单，端上拿到的字段与真机完全不同（只是没人用到，所以一直没暴露）
    // 复用 toGroupRequest 里那份换算（价格要按当前市场换算，自己再写一遍必漂）
    const mine = toGroupRequest(seed).quotes.find((q) => q.merchant.merchantNo === merchantNo)!;
    return delay(mine);
  },
};
