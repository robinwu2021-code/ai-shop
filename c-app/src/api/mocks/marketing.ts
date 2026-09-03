// 营销：券、活动、拼团 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { assertTransition, buildGroupBuy, db, delay, findGoodsSeed, nextNo, persist, pick, pushMessage, toGoods } from "@shared/mock/db";
import type { Order, PickupPoint } from "@shared/types";
import {
  grantPointsOnComplete,
  maskAddress,
  pushTimeline,
  toPickupOrder,
} from "./_shared";
import type { ShopApi } from "../contract";

export const marketingMock: Pick<ShopApi,
  "couponList"
  | "myStoreCoupons"
  | "myMemberships"
  | "setMembershipReach"
  | "receiveCoupon"
  | "groupBuyList"
  | "groupBuyDetail"
  | "createGroupBuy"
  | "myHostedGroups"
  | "groupPickupOrders"
  | "confirmGroupBatch"
  | "verifyGroupPickup"
  | "joinGroupBuy"
> = {
  // ---------------------------------------------------------------- 营销
  async couponList() {
    return delay(
      db.couponSeeds.map((c) => ({
        ...c,
        title: pick(c.title),
        scopeDesc: pick(c.scopeDesc),
      })),
    );
  },

  /**
   * 商家发给我的券（新模型）。mock 里种两张演示这批券**与领券中心那批的差别**：
   * 一张下单抵扣（没有码），一张到店出示（有码、5 次的次卡）。
   */
  async myStoreCoupons() {
    const day = 86400_000;
    return delay([
      {
        userCouponNo: "PU-DEMO-1",
        couponNo: "PC-DEMO-1",
        title: "老客回归 · 满 30 减 5",
        benefitText: "减 5 元",
        entityNo: "M001",
        redeemMode: "ORDER",
        // 下单抵扣的券**不给码** —— 给了顾客会拿着手机去店里问
        redeemCode: null,
        minAmountMinor: 3000,
        timesTotal: 1,
        timesUsed: 0,
        remaining: 1,
        expireAt: Date.now() + 6 * day,
        status: "UNUSED",
        usableNow: true,
      },
      {
        userCouponNo: "PU-DEMO-2",
        couponNo: "PC-DEMO-2",
        title: "豆浆五杯卡 · 到店出示",
        benefitText: "凭券兑换",
        entityNo: "M001",
        redeemMode: "STORE_CODE",
        redeemCode: "DEMO2345",
        minAmountMinor: null,
        timesTotal: 5,
        timesUsed: 2,
        remaining: 3,
        expireAt: Date.now() + 25 * day,
        status: "UNUSED",
        usableNow: true,
      },
      {
        userCouponNo: "PU-DEMO-3",
        couponNo: "PC-DEMO-3",
        title: "开业尝鲜 · 减 2 元",
        benefitText: "减 2 元",
        entityNo: "M001",
        redeemMode: "ORDER",
        redeemCode: null,
        minAmountMinor: null,
        timesTotal: 1,
        timesUsed: 0,
        remaining: 1,
        // 过期的也留在券包里：突然少一张，用户的第一反应是「平台把我的券吞了」
        expireAt: Date.now() - 2 * day,
        status: "UNUSED",
        usableNow: false,
      },
    ]);
  },

  /**
   * 我是哪几家店的会员。mock 里给两家：一家开着消息、一家已经关了 ——
   * 只给一家的话，看不出这个开关是**每家一个**的。
   */
  async myMemberships() {
    return delay(db.myMemberships.map((m) => ({ ...m })));
  },

  async setMembershipReach(entityNo, optOut) {
    const m = db.myMemberships.find((x) => x.entityNo === entityNo);
    if (!m) throw new Error("你还不是这家店的会员");
    m.reachOptOut = optOut;
    persist();
    return delay(undefined as unknown as void);
  },

  async receiveCoupon(couponNo) {
    const c = db.couponSeeds.find((x) => x.couponNo === couponNo);
    if (!c) throw new Error("优惠券不存在");
    if (c.received) throw new Error("已领取过该券");
    c.received = true;
    persist();
    pushMessage("MARKETING", "领券成功", `${pick(c.title)} 已放入你的券包`);
    // 领券返回的是**领到手的那一张**（UserCoupon），不是券模板 —— 与后端同形
    return delay({
      userCouponNo: `UC-${c.couponNo}`,
      coupon: { ...c, title: pick(c.title), scopeDesc: pick(c.scopeDesc) },
      status: "UNUSED",
      usableNow: true,
      receivedAt: Date.now(),
    });
  },

  /** 只返回**当前自提点**的团 —— 成团单位是自提点，别的点的团与我无关 */
  async groupBuyList(pickupNo) {
    const list = db.groupSeeds
      .filter((g) => !pickupNo || g.pickupNo === pickupNo)
      .map(buildGroupBuy);
    return delay(list);
  },

  async groupBuyDetail(groupNo) {
    const seed = db.groupSeeds.find((x) => x.groupNo === groupNo);
    if (!seed) throw new Error("拼团不存在");
    return delay(buildGroupBuy(seed));
  },

  /** 用户自发发起一个团：绑定发起人当前的自提点，发起人自动算第一个参与者 */
  async createGroupBuy(goodsNo, pickupNo, neighbor) {
    const goods = toGoods(findGoodsSeed(goodsNo));
    if (!goods.groupBuy) throw new Error("该商品未开放拼团");
    const groupNo = nextNo("GB");

    // 「送到我家」= 建一个**团粒度的临时自提点**（ADR-005 §3）。
    // 它随团创建、随团消失，不进社区主数据；承接的是发起人本人，**零报酬**。
    const neighborPickup = neighbor?.toMyHome
      ? ({
          pickupNo: nextNo("NP"),
          type: "NEIGHBOR" as const,
          ownerType: "USER" as const,
          ownerNo: db.user.cUserNo,
          scope: "GROUP_INSTANCE" as const,
          groupNo,
          name: `${db.user.nickname}家`,
          // 成团前只到楼栋，付款后才给完整门牌（B13）—— 未成团的团不该暴露住址
          address: maskAddress(neighbor.address),
          timeSlot: neighbor.timeSlot,
          // 邻里自提必须为零：有报酬那个邻居就是团长，ADR-004 消掉的合规问题会回来
          feeMode: "NONE" as const,
          serviceFeePerItemMinor: 0,
          serviceFeeRate: 0,
        } satisfies PickupPoint)
      : undefined;

    const seed = {
      groupNo,
      goodsNo,
      pickupNo,
      neighborPickup,
      ownedByMe: true,
      initiatorNickname: db.user.nickname,
      initiatorAvatar: db.user.avatar,
      createdAt: Date.now(),
      members: [{ avatar: db.user.avatar, nickname: db.user.nickname }],
      joined: true,
    };
    db.groupSeeds.unshift(seed);
    persist();
    return delay(buildGroupBuy(seed));
  },

  // ---------------------------------------------------------------- 邻里自提（发起人侧）
  async myHostedGroups() {
    return delay(db.groupSeeds.filter((g) => g.ownedByMe).map(buildGroupBuy));
  },

  async groupPickupOrders(groupNo) {
    return delay(db.orders.filter((o) => o.groupNo === groupNo).map(toPickupOrder));
  },

  async confirmGroupBatch(groupNo) {
    const seed = db.groupSeeds.find((g) => g.groupNo === groupNo);
    if (!seed) throw new Error("团不存在");
    if (!seed.ownedByMe) throw new Error("只有发起人能签收");
    seed.received = true;

    // 整批签收 → 参团者收到「到货了」通知。
    // 之后个别缺损照常走售后 —— 签收不等于放弃售后权利
    const changed: Order[] = [];
    for (const o of db.orders) {
      if (o.groupNo !== groupNo || o.status !== "PAID") continue;
      assertTransition(o.status, "FULFILLING");
      o.status = "FULFILLING";
      pushTimeline(o, "已送到发起人家，请按约定时段取货");
      pushMessage(
        "TRADE",
        "团购的货到了",
        `到 ${seed.neighborPickup?.name ?? "取货点"} 取，时段 ${seed.neighborPickup?.timeSlot ?? "—"}`,
        `/pages/order/index?orderNo=${o.orderNo}`,
      );
      changed.push(o);
    }
    persist();
    // 返回团本身（与后端同形）；「签收了几单」由端上按签收前的在途数说
    return delay(buildGroupBuy(seed));
  },

  async verifyGroupPickup(groupNo, code) {
    const seed = db.groupSeeds.find((g) => g.groupNo === groupNo);
    if (!seed?.ownedByMe) throw new Error("只有发起人能核销本团");
    const o = db.orders.find((x) => x.verifyCode === code);
    if (!o) throw new Error("核销码无效");
    // **作用域严格限本团**（E16）：发起人只能核销自己发起的那个团，
    // 拿到别人的码也核不掉 —— 这跟商家履约台是两套权限
    if (o.groupNo !== groupNo) throw new Error("这单不属于本团");
    if (o.status === "COMPLETED") throw new Error("该订单已核销");
    if (o.status === "PAID") {
      o.status = "FULFILLING";
      pushTimeline(o, "已送到发起人家");
    }
    assertTransition(o.status, "COMPLETED");
    o.status = "COMPLETED";
    pushTimeline(o, "邻居已取走");
    grantPointsOnComplete(o);
    persist();
    return delay(toPickupOrder(o));
  },

  /** 参团：加入后重算。达到新档时，先参团的人同享 —— 差价退回由结算侧处理 */
  async joinGroupBuy(groupNo, qty) {
    const seed = db.groupSeeds.find((x) => x.groupNo === groupNo);
    if (!seed) throw new Error("拼团不存在");
    if (seed.joined) throw new Error("你已参团");
    const before = buildGroupBuy(seed);
    seed.members = [
      ...seed.members,
      { avatar: db.user.avatar, nickname: db.user.nickname },
    ];
    seed.joined = true;
    persist();
    const after = buildGroupBuy(seed);
    return delay({
      group: after,
      /** 本次参团是否正好把团凑成 —— 成团后先参团的人同享团购价，差价退回 */
      justReached: after.reached && !before.reached,
      refundPerMember: after.reached && !before.reached
        ? after.basePrice - after.groupPrice
        : 0,
    });
  },
};
