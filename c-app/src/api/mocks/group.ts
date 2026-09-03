// 邻里求团与报价 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, findGoodsSeed, nextNo, persist, toGoods, toGroupRequest } from "@shared/mock/db";
import type { Review } from "@shared/types";
import { SERVICE_SCOPE } from "@shared/utils/constants";
import type { ShopApi } from "../contract";

export const groupMock: Pick<ShopApi,
  "requestList"
  | "requestDetail"
  | "createRequest"
  | "toggleInterest"
  | "chooseQuote"
  | "confirmRequest"
  | "createReview"
  | "masterData"
  | "merchantApply"
  | "myMerchantApply"
> = {
  // ------------------------------------------------------------ 邻里求团
  async requestList(pickupNo) {
    const list = db.requests
      .filter((r) => !pickupNo || r.pickupNo === pickupNo)
      .map(toGroupRequest)
      // 有报价的排前面 —— 对邻居来说「已经有人报价了」比「刚发起」更值得点进去
      .sort(
        (a, b) =>
          b.quotes.length - a.quotes.length ||
          b.interestedCount - a.interestedCount ||
          b.createdAt - a.createdAt,
      );
    return delay(list);
  },

  async requestDetail(requestNo) {
    const seed = db.requests.find((r) => r.requestNo === requestNo);
    if (!seed) throw new Error("需求不存在");
    return delay(toGroupRequest(seed));
  },

  async createRequest(payload) {
    const seed = {
      requestNo: nextNo("RQ"),
      initiatorNickname: db.user.nickname,
      initiatorAvatar: db.user.avatar,
      pickupNo: payload.pickupNo,
      title: payload.title,
      desc: payload.desc,
      images: [] as string[],
      expectQty: payload.expectQty,
      budgetMinor: payload.budgetMinor,
      status: "COLLECTING" as const,
      // 发起人自己算第一个意向
      interestedCount: 1,
      interested: true,
      neighbours: [{ avatar: db.user.avatar, nickname: db.user.nickname }],
      quotes: [],
      createdAt: Date.now(),
      expireAt: Date.now() + 7 * 86400_000,
    };
    db.requests.unshift(seed);
    persist();
    return delay(toGroupRequest(seed));
  },

  async toggleInterest(requestNo) {
    const seed = db.requests.find((r) => r.requestNo === requestNo);
    if (!seed) throw new Error("需求不存在");
    seed.interested = !seed.interested;
    if (seed.interested) {
      seed.interestedCount += 1;
      seed.neighbours = [
        ...seed.neighbours,
        { avatar: db.user.avatar, nickname: db.user.nickname },
      ];
    } else {
      seed.interestedCount = Math.max(0, seed.interestedCount - 1);
      seed.neighbours = seed.neighbours.filter((n) => n.nickname !== db.user.nickname);
    }
    persist();
    return delay(toGroupRequest(seed));
  },

  /** 选定报价 = 需求转供给。真实后端在这一步生成商品与团，这里只标记状态 */
  async chooseQuote(requestNo, quoteNo) {
    const seed = db.requests.find((r) => r.requestNo === requestNo);
    if (!seed) throw new Error("需求不存在");
    if (seed.initiatorNickname !== db.user.nickname) throw new Error("只有发起人可以选定报价");
    const q = seed.quotes.find((x) => x.quoteNo === quoteNo);
    if (!q) throw new Error("报价不存在");
    if (seed.interestedCount < q.minCount) {
      throw new Error(`还差 ${q.minCount - seed.interestedCount} 人达到该报价的起订量`);
    }
    seed.quotes.forEach((x) => {
      x.chosen = x.quoteNo === quoteNo;
      // 选定即锁价：之后下单一律用这个快照价，商家改不了 —— 加价在技术上做不到
      x.locked = x.chosen;
    });
    seed.status = "LOCKED";
    seed.lockedPriceMinor = q.priceMinor;
    // +1 只是意向，转团后每个人要各自确认才算下单
    seed.confirmed = false;
    seed.confirmedCount = 0;
    persist();
    return delay(toGroupRequest(seed));
  },

  /**
   * 选定报价后的二次确认下单。
   * +1 是「我也想要」，不是承诺 —— 直接按 +1 人数扣款会炸，所以必须各自确认。
   */
  async confirmRequest(requestNo) {
    const seed = db.requests.find((r) => r.requestNo === requestNo);
    if (!seed) throw new Error("需求不存在");
    if (seed.status !== "LOCKED") throw new Error("还没有选定报价");
    if (seed.confirmed) throw new Error("你已确认");
    seed.confirmed = true;
    seed.confirmedCount = (seed.confirmedCount ?? 0) + 1;
    persist();
    return delay(toGroupRequest(seed));
  },

  /**
   * 发表评价。评价一旦落库就会进入商家评分的计算，所以这里同时校验：
   * 订单必须已完成、且未评价过 —— 否则刷单能直接刷分。
   */
  async createReview(payload) {
    const o = db.orders.find((x) => x.orderNo === payload.orderNo);
    if (!o) throw new Error("订单不存在");
    if (o.status !== "COMPLETED") throw new Error("订单完成后才能评价");
    if (o.reviewed) throw new Error("该订单已评价");
    const item = o.items.find((it) => it.goodsNo === payload.goodsNo && !it.isGift);
    const review: Review = {
      reviewNo: nextNo("RV"),
      goodsNo: payload.goodsNo,
      merchantNo: item?.merchantNo ?? toGoods(findGoodsSeed(payload.goodsNo)).merchant.merchantNo,
      nickname: db.user.nickname,
      avatar: db.user.avatar,
      rating: payload.rating,
      content: payload.content,
      images: payload.images,
      spec: item?.spec ?? "",
      createdAt: Date.now(),
      likeCount: 0,
      liked: false,
      // 没细评就按总分回填三维：平台的评分权重需要维度分作输入，
      // 缺维度的评价会让权重形同虚设（等于「有人细评就算权重、没人细评就不算」）
      scores: payload.scores ?? {
        goods: payload.rating,
        fulfillment: payload.rating,
        service: payload.rating,
      },
    };
    db.reviews.unshift(review);
    o.reviewed = true;
    persist();
    return delay(review);
  },

  async masterData() {
    // 带一个不允许小微的行业，否则「行业决定能否选小微」在 mock 下永远看不出效果
    return delay({
      industries: [
        { industry: "FRESH", name: "生鲜果蔬", microAllowed: true },
        { industry: "GROCERY", name: "粮油日用", microAllowed: true },
        { industry: "BAKERY", name: "烘焙熟食", microAllowed: true },
        { industry: "ONLINE_SERVICE", name: "线上服务", microAllowed: false },
      ],
      subjects: [
        { subjectType: "NATURAL_PERSON" as const, name: "自然人", needLicense: false,
          industryGated: true, settleAccountType: "PERSONAL_BANK_CARD" as const },
        { subjectType: "INDIVIDUAL" as const, name: "个体工商户", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
        { subjectType: "ENTERPRISE" as const, name: "企业", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
      ],
      channels: [{ payChannel: "WECHAT", name: "微信支付", enabled: true, payMethods: ["JSAPI"] }],
      // 一期只开「仅本社区」：与 B 端 mock、后端 sys_setting 的白名单同一口径。
      // 写死三档的话，mock 下能选到一个真实环境必被拒的档，而那种问题只有联调才会撞见
      serviceScopes: [SERVICE_SCOPE.COMMUNITY],
    });
  },

  async merchantApply(payload) {
    /*
     * 一人同时只能有一份进行中的申请 —— 表单页重复点击是常态。
     * 真实后端靠 uk_apply_active_owner 唯一键挡住（先查后插必然有竞态）。
     */
    if (db.merchantApply && ["PENDING", "REVIEWING"].includes(db.merchantApply.status)) {
      throw new Error("你已有一份进行中的入驻申请");
    }
    db.merchantApply = {
      ...payload,
      applyNo: nextNo("MA"),
      status: "PENDING",
      createdAt: Date.now(),
    };
    persist();
    return delay({ ...db.merchantApply });
  },

  async myMerchantApply() {
    // 没申请过返回 null 而不是报错 —— 「没申请过」是正常状态，不是异常
    return delay(db.merchantApply ? { ...db.merchantApply } : null);
  },
};
