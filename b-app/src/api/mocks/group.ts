// 团购与求团报价 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { allCommunitySeeds, buildGroupBuy, db, delay, findGoodsSeed, nextNo, persist, pick, toGoods, toGroupRequest } from "@shared/mock/db";
import { ApiError } from "@shared/net/http-client";
import { MERCHANT_LOGO_FALLBACK } from "@shared/utils/constants";
import {
  requireMerchant,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const groupMock: Pick<MerchantApi,
  "mGroupList"
  | "mCreateGroup"
  | "mGroup"
  | "mDissolveGroup"
  | "mGroupPickups"
  | "mRequestList"
  | "mQuote"
> = {
  // ---------------------------------------------------------------- 团购与报价
  async mGroupList(status) {
    const merchantNo = db.merchant.merchantNo;
    return delay(
      db.groupSeeds
        .map(buildGroupBuy)
        .filter((g) => g.merchant.merchantNo === merchantNo)
        .filter((g) => !status || g.status === status),
    );
  },

  async mGroup(groupNo) {
    const seed = db.groupSeeds.find((g) => g.groupNo === groupNo);
    if (!seed) throw new ApiError(10404, "团不存在");
    return delay(buildGroupBuy(seed));
  },

  async mDissolveGroup(groupNo) {
    requireMerchant();
    const seed = db.groupSeeds.find((g) => g.groupNo === groupNo);
    if (!seed) throw new ApiError(10404, "团不存在");
    const g = buildGroupBuy(seed);
    // 与后端同一口径：只能散还在拼的团；已成团的买家已经在等货了
    if (g.status === "FORMED") throw new ApiError(10409, "已成团，不能散团");
    seed.failed = true;
    persist();
    return delay(buildGroupBuy(seed));
  },

  async mGroupPickups() {
    return delay(allCommunitySeeds().flatMap((c) => c.pickups).slice(0, 3).map((p) => ({
      pickupNo: p.pickupNo,
      name: pick(p.name),
      address: "",
      type: "NEIGHBOR",
      status: "ACTIVE",
    })));
  },

  async mCreateGroup(req) {
    requireMerchant();
    const goods = toGoods(findGoodsSeed(req.goodsNo));
    // 真后端从活动取价与人数；mock 的团价仍挂在商品上（buildGroupBuy 从那儿算）
    if (!goods.groupBuy) throw new ApiError(20004, "这件商品不在进行中的拼团活动里");
    const seed = {
      groupNo: nextNo("GB"),
      goodsNo: req.goodsNo,
      // 成团单位是自提点：拼的是一车送到一个点的成本
      pickupNo: req.pickupNo ?? db.merchant.pickupNo ?? allCommunitySeeds()[0]!.pickups[0]!.pickupNo,
      initiatorNickname: db.merchant.name || "商家",
      initiatorAvatar: db.merchant.logo || MERCHANT_LOGO_FALLBACK,
      createdAt: Date.now(),
      members: [],
      joined: false,
      activityName: "拼团",
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
