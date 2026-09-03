// C 端 mock 的**内部工具与状态**：种子派生、状态机断言、演示用的模块级变量。
//
// <p>它就是原来 `mock.ts` 的头部 —— 拆分时整体搬过来，一个字没改，
// 只把顶层声明加上 `export`，好让各域的替身文件能用。
// mock 实现：真改 db（重开能读回），状态机强制，非法迁移抛错。
// 本地化在「出口」处完成（toGoods/toCommunity 按当前语言拍平），对齐真实后端按 Accept-Language 返回。
import {
  allGoods,
  assertTransition,
  db,
  delay,
  findGoodsSeed,
  nextNo,
  paginate,
  persist,
  pick,
  pointBalance,
  pushMessage,
  pushPoint,
  toCommunity,
  allCommunitySeeds,
  buildGroupBuy,
  toGoods,
  toGroupRequest,
  toMerchant,
  merchantBrief,
} from "@shared/mock/db";
import { earnPointsFor, pricingFor } from "@shared/strategies/pricing";
import { fulfillmentFor } from "@shared/strategies/fulfillment";
import { CATEGORY_TYPE, FULFILLMENT, PAY_MODE, POINTS, SERVICE_SCOPE, TRADE_RULES } from "@shared/utils/constants";
import { currentCurrency } from "@shared/utils/money";
import { isPhone } from "@shared/utils/validate";
import { pointsExpireAt } from "@shared/utils/datetime";
import { defaultFulfillment } from "@shared/utils/goods";
import { buyNGetM, giftQtyFor } from "@shared/utils/promotion";
import type { CreateOrderReq, GoodsQuery, ShopApi } from "../contract";
import type {
  InvoiceRequest,
  AfterSaleReason,
  Coupon,
  GroupPickupOrder,
  FrequentItem,
  Order,
  OrderItem,
  PageQuery,
  PickupPoint,
  RegionNode,
  Review,
} from "@shared/types";

/**
 * 地址脱敏：成团前只显示到楼栋（B13）。
 * 未成团的团不该把发起人的完整门牌暴露给所有看到这个团的人。
 */
export function maskAddress(address: string): string {
  const m = address.match(/^(.{0,12}?[栋幢号楼])/);
  return m ? `${m[1]}（成团后显示门牌）` : `${address.slice(0, 8)}…（成团后显示门牌）`;
}

export function findOrder(orderNo: string): Order {
  const o = db.orders.find((x) => x.orderNo === orderNo);
  if (!o) throw new Error(`订单不存在：${orderNo}`);
  return o;
}

/**
 * 退款落账。**三件事必须同时做**，漏一件就是账不平：
 *   1. 订单置 REFUNDED
 *   2. 收回已发放的积分（否则用户「下单→拿积分→退款」白赚）
 *   3. 返还抵扣掉的积分（用户没买成，凭什么扣他的分）
 */
export function settleRefund(o: Order, label: string) {
  assertTransition(o.status, "REFUNDED");
  o.status = "REFUNDED";
  if (o.afterSale) {
    o.afterSale.status = "REFUNDED";
    o.afterSale.updatedAt = Date.now();
  }
  pushTimeline(o, label);
  pushMessage(
    "TRADE",
    "退款已到账",
    "款项已原路退回，到账时间以支付渠道为准",
    `/pages/order/index?orderNo=${o.orderNo}`,
  );
  if (o.pointsGranted && o.amount.pointsEarn > 0) {
    pushPoint(db.points, "EXPIRE", -o.amount.pointsEarn, "订单退款收回", o.orderNo);
    o.pointsGranted = false;
  }
  if (o.amount.pointsUsed > 0) {
    pushPoint(db.points, "REFUND", o.amount.pointsUsed, "订单退款返还", o.orderNo);
  }
}

/**
 * 订单 → 本团待取单。**发起人只需要「谁的、几件、核销码」**，不需要整张订单。
 * 后端返回的一直是这个裁剪过的形状，mock 此前直接给整张 Order —— 于是
 * 「mock 下字段都在、真机上 o.orderNo 是 undefined」。
 */
export function toPickupOrder(o: Order): GroupPickupOrder {
  return {
    subOrderNo: o.orderNo,
    buyerNickname: db.user.nickname,
    verifyCode: o.verifyCode ?? "",
    status: o.status,
    items: o.items.map((it) => ({
      goodsNo: it.goodsNo,
      title: it.title,
      spec: it.spec,
      qty: it.qty,
    })),
  };
}

export function pushTimeline(order: Order, label: string) {
  order.timeline.push({ status: order.status, label, at: Date.now() });
}

/** 卡券购买成功 → 入卡包。储值卡记额度，次卡记次数。 */
export function issueCard(order: Order, item: OrderItem) {
  const g = toGoods(findGoodsSeed(item.goodsNo));
  if (!g.card) return;
  for (let i = 0; i < item.qty; i += 1) {
    db.cards.unshift({
      cardNo: nextNo("CD"),
      goodsNo: g.goodsNo,
      title: g.title,
      cover: g.cover,
      balanceMinor: g.card.faceValueMinor,
      timesLeft: g.card.timesTotal,
      expireAt: Date.now() + g.card.validDays * 86400_000,
      currency: order.amount.currency,
    });
  }
}

/**
 * 订单完成时发放积分。
 * **完成时才发，不是支付时** —— 支付即发的话，用户可以「下单→拿积分→立刻花掉→退款」，
 * 积分已经花出去了追不回来。
 *
 * 同时给商家侧记一笔「收到积分」：用户用积分抵掉的那部分，商家实际收的是积分，
 * 由平台后续兑付成现金（这正是积分成为平台负债的地方，见 ADR-006）。
 */
export function grantPointsOnComplete(o: Order) {
  if (o.pointsGranted) return;
  if (o.amount.pointsEarn > 0) {
    pushPoint(db.points, "EARN", o.amount.pointsEarn, "消费获得", o.orderNo);
  }
  if (o.amount.pointsUsed > 0) {
    pushPoint(
      db.merchantPoints,
      "RECEIVE",
      o.amount.pointsUsed,
      "用户积分抵扣（待平台兑付）",
      o.orderNo,
    );
  }
  o.pointsGranted = true;
}

/** 账户视图由流水推导，不单独存余额 */
export function buildAccount(ledger: typeof db.points) {
  const balance = pointBalance(ledger);
  const totalEarned = ledger.filter((r) => r.points > 0).reduce((s, r) => s + r.points, 0);
  const totalUsed = ledger.filter((r) => r.points < 0).reduce((s, r) => s - r.points, 0);
  // 滚动到期：从**最近一次积分变动**起算，整个账户一个到期日（V30）。
  // 到期时是全部清零，所以 expiringSoon 要么是全部余额，要么是 0
  const lastActive = ledger[0]?.at;
  const expiringAt = lastActive
    ? pointsExpireAt(lastActive, POINTS.inactiveDays)
    : undefined;
  const soon =
    expiringAt && expiringAt - Date.now() < 30 * 86400_000 ? balance : 0;
  // 待生效：售后期未满的那部分。mock 里用最近一笔 EARN 模拟，
  // 让 C 端能看到「可用 / 待生效」两个数分开的样子
  const latestEarn = ledger.find((r) => r.points > 0);
  const pending = latestEarn && Date.now() - latestEarn.at < 7 * 86400_000 ? latestEarn.points : 0;
  return {
    balance,
    pendingBalance: pending,
    pendingActivateAt: pending ? latestEarn!.at + 7 * 86400_000 : undefined,
    totalEarned,
    totalUsed,
    expiringSoon: soon,
    expiringAt,
  };
}

/**
 * 取商家的服务半径信息。Goods 上挂的是 MerchantBrief（不含范围/距离），
 * 这里直接查 seed —— 不走 toMerchant，那会连带重算评分，列表里逐条算太浪费。
 */
export function distanceOf(g: { merchant: { merchantNo: string } }): number {
  return db.merchantSeeds.find((m) => m.merchantNo === g.merchant.merchantNo)?.distanceFromCM001 ?? 0;
}

/**
 * 从历史订单聚合常买清单。**店内（C-ST-02）与首页跨商家共用这一份** ——
 * 两处的语义完全相同，差别只是 keep 这个作用域判定，分开写迟早会漂移。
 * 频次优先、其次最近买过：「常买」的语义是次数，不是时间。
 */
export function aggregateFrequent(keep: (goodsNo: string) => boolean): FrequentItem[] {
  const map = new Map<string, { item: OrderItem; times: number; lastAt: number }>();
  for (const o of db.orders) {
    if (o.status === "CANCELLED") continue;
    for (const it of o.items) {
      if (it.isGift) continue; // 赠品是促销算出来的，不是「我买的东西」
      if (!keep(it.goodsNo)) continue;
      const cur = map.get(it.skuNo);
      if (cur) {
        cur.times += 1;
        cur.lastAt = Math.max(cur.lastAt, o.createdAt);
      } else {
        map.set(it.skuNo, { item: it, times: 1, lastAt: o.createdAt });
      }
    }
  }
  const rows = [...map.values()].map(({ item, times, lastAt }) => {
    const g = toGoods(findGoodsSeed(item.goodsNo));
    const sku = g.skus.find((k) => k.skuNo === item.skuNo);
    return {
      goodsNo: item.goodsNo,
      skuNo: item.skuNo,
      title: g.title,
      cover: g.cover,
      spec: sku?.spec ?? item.spec,
      price: sku?.price ?? item.price,
      lastPrice: item.price,
      times,
      lastAt,
      invalid: !g.onSale || (sku?.stock ?? 0) <= 0,
    };
  });
  return rows.sort((a, b) => b.times - a.times || b.lastAt - a.lastAt);
}

/**
 * 商家的经营范围是否覆盖某社区 —— **商品可见性的第一道闸门**。
 * 不传社区 = 不限制（搜索等主动查找的场景）。
 *
 * 三档各判各的，不做兜底放行：查不到商家或范围配错，一律**不可达**。
 * 反过来（配错就放行）会让货悄悄卖到送不到的地方，用户下单后才发现提不了 —— 直接是退款。
 */
export function reaches(merchantNo: string, communityNo?: string): boolean {
  if (!communityNo) return true;
  const seed = db.merchantSeeds.find((m) => m.merchantNo === merchantNo);
  if (!seed) return false;
  switch (seed.serviceScope) {
    case SERVICE_SCOPE.PLATFORM:
      return true;
    case SERVICE_SCOPE.CITY: {
      const city = allCommunitySeeds().find((c) => c.communityNo === communityNo)?.cityCode;
      return !!city && seed.serviceCityCode === city;
    }
    case SERVICE_SCOPE.COMMUNITY:
      return seed.serviceCommunityNos.includes(communityNo);
    default:
      return false;
  }
}

/**
 * 按售后单号找到承载它的订单。售后虽然是独立资源，但 mock 里仍存在 Order 上 ——
 * 真实后端是独立表，这里只要**寻址方式**与契约一致即可，存储形态不必强行照搬。
 */
export function findOrderByAfterSale(afterSaleNo: string): Order {
  const o = db.orders.find((x) => x.afterSale?.afterSaleNo === afterSaleNo);
  if (!o) throw new Error("售后单不存在");
  return o;
}

