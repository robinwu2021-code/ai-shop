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
import { pointsExpireAt } from "@shared/utils/datetime";
import { defaultFulfillment } from "@shared/utils/goods";
import { buyNGetM, giftQtyFor } from "@shared/utils/promotion";
import type { CreateOrderReq, GoodsQuery, ShopApi } from "./contract";
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
  Review,
} from "@shared/types";

/**
 * 地址脱敏：成团前只显示到楼栋（B13）。
 * 未成团的团不该把发起人的完整门牌暴露给所有看到这个团的人。
 */
function maskAddress(address: string): string {
  const m = address.match(/^(.{0,12}?[栋幢号楼])/);
  return m ? `${m[1]}（成团后显示门牌）` : `${address.slice(0, 8)}…（成团后显示门牌）`;
}

function findOrder(orderNo: string): Order {
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
function settleRefund(o: Order, label: string) {
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
function toPickupOrder(o: Order): GroupPickupOrder {
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

function pushTimeline(order: Order, label: string) {
  order.timeline.push({ status: order.status, label, at: Date.now() });
}

/** 卡券购买成功 → 入卡包。储值卡记额度，次卡记次数。 */
function issueCard(order: Order, item: OrderItem) {
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
function grantPointsOnComplete(o: Order) {
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
function buildAccount(ledger: typeof db.points) {
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
function distanceOf(g: { merchant: { merchantNo: string } }): number {
  return db.merchantSeeds.find((m) => m.merchantNo === g.merchant.merchantNo)?.distanceFromCM001 ?? 0;
}

/**
 * 从历史订单聚合常买清单。**店内（C-ST-02）与首页跨商家共用这一份** ——
 * 两处的语义完全相同，差别只是 keep 这个作用域判定，分开写迟早会漂移。
 * 频次优先、其次最近买过：「常买」的语义是次数，不是时间。
 */
function aggregateFrequent(keep: (goodsNo: string) => boolean): FrequentItem[] {
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
function reaches(merchantNo: string, communityNo?: string): boolean {
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
function findOrderByAfterSale(afterSaleNo: string): Order {
  const o = db.orders.find((x) => x.afterSale?.afterSaleNo === afterSaleNo);
  if (!o) throw new Error("售后单不存在");
  return o;
}

export const mockApi: ShopApi = {
  // ---------------------------------------------------------------- 积分
  async pointAccount() {
    return delay(buildAccount(db.points));
  },

  async pointRecords() {
    return delay([...db.points]);
  },

  async pointsDeductible(q) {
    // 与服务端同一套判据：四级开关 → 上限 → 余额，三者取小
    const acc = buildAccount(db.points);
    const cap = Math.floor(q.payableMinor * POINTS.maxDeductRatio) * POINTS.perMinor;
    const maxPoints = Math.min(acc.balance, cap);
    return delay({
      maxPoints,
      maxAmountMinor: Math.floor(maxPoints / POINTS.perMinor),
      balance: acc.balance,
    });
  },

  // ---------------------------------------------------------------- 用户
  async sendOtp(phone: string) {
    // mock 不真的发短信；验证码固定 1234（登录页也照这条口径提示）
    if (!/^\d{11}$/.test(phone)) throw new Error("手机号格式不对");
    await delay(undefined);
  },

  async login(req) {
    // 进店归因：从店铺码/店铺分享进来时带 merchantNo，写入「常去的店」。
    // 它同时决定订单的 trafficSource 与费率档（ADR-004 §5.4 / §6）
    if (req.merchantNo) db.user.merchantNo = req.merchantNo;
    return delay({ token: `mock-token-${Date.now()}`, user: { ...db.user } });
  },

  async profile() {
    return delay({ ...db.user });
  },

  /** mock 下没有服务端会话可作废，直接放行。真实环境由后端 revoke 令牌 */
  async logout() {
    return delay(undefined as void);
  },

  async deregister() {
    db.user.phone = "";
    db.user.nickname = "已注销用户";
    return delay(undefined as unknown as void);
  },

  async bindPhone(phone: string) {
    db.user.phone = phone;
    return delay({ ...db.user });
  },

  async bindPhoneByWx() {
    // mock 侧一键授权恒可用，且给一个固定号 —— 真后端桩通道是**返回 null 并报 70027**
    db.user.phone = "13800138000";
    return delay({ ...db.user });
  },

  async phoneCapable() {
    return delay({ capable: true });
  },

  async bindCommunity(communityNo, pickupNo) {
    const seed = allCommunitySeeds().find((c) => c.communityNo === communityNo);
    if (!seed) throw new Error("社区不存在");
    const pk = seed.pickups.find((p) => p.pickupNo === pickupNo);
    if (!pk) throw new Error("自提点不存在");
    db.user.communityNo = communityNo;
    db.user.pickupNo = pickupNo;
    // 自提点由入驻商家承接（ADR-005）：绑点的同时把承接商家记为「常去的店」
    db.user.merchantNo = pk.hostMerchantNo;
    return delay({ ...db.user });
  },

  // ---------------------------------------------------------------- 地址簿
  async addressList() {
    return delay([...db.addresses]);
  },

  async saveAddress(payload) {
    if (payload.addressId) {
      const i = db.addresses.findIndex((a) => a.addressId === payload.addressId);
      if (i < 0) throw new Error("地址不存在");
      db.addresses[i] = { ...db.addresses[i]!, ...payload, addressId: payload.addressId };
    } else {
      db.addresses.push({ ...payload, addressId: nextNo("AD") });
    }
    // 设了默认就把别的取消 —— 默认地址只能有一个
    if (payload.isDefault) {
      const target = payload.addressId ?? db.addresses[db.addresses.length - 1]!.addressId;
      db.addresses.forEach((a) => (a.isDefault = a.addressId === target));
    }
    // 第一条地址自动成为默认，省得用户还要再点一次
    if (db.addresses.length === 1) db.addresses[0]!.isDefault = true;
    persist();
    return delay([...db.addresses]);
  },

  async removeAddress(addressId) {
    const wasDefault = db.addresses.find((a) => a.addressId === addressId)?.isDefault;
    db.addresses = db.addresses.filter((a) => a.addressId !== addressId);
    // 删掉的是默认地址就把第一条顶上，避免出现「一条都不是默认」的状态
    if (wasDefault && db.addresses[0]) db.addresses[0].isDefault = true;
    persist();
    return delay([...db.addresses]);
  },

  async setDefaultAddress(addressId) {
    db.addresses.forEach((a) => (a.isDefault = a.addressId === addressId));
    persist();
    return delay([...db.addresses]);
  },

  // ---------------------------------------------------------------- 社区
  async nearbyCommunities() {
    return delay(allCommunitySeeds().map(toCommunity));
  },

  async allCommunities() {
    // mock 侧两者同源：真后端的差别是 nearby 带半径过滤，而 mock 只有一个城市的种子
    return delay(allCommunitySeeds().map(toCommunity));
  },

  async openRegions() {
    // 演示数据只有一个区。真后端是从社区的 region_code 聚合出来的
    return delay([
      {
        regionCode: "330106",
        name: "西湖区",
        cityCode: "3301",
        cityName: "杭州市",
        communityCount: allCommunitySeeds().length,
      },
    ]);
  },

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
    return delay(toGoods(findGoodsSeed(goodsNo)));
  },

  // ---------------------------------------------------------------- 购物车
  async cartList() {
    // 购物车里的 title/spec 是加购当时的语言快照，按当前语言重算一遍
    // （真实后端同理：购物车存 goodsNo/skuNo，返回时按 Accept-Language 本地化）
    db.cart = db.cart.map((it) => {
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
      };
    });
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
    return delay([...db.cart]);
  },

  async cartUpdate(skuNo, qty) {
    const item = db.cart.find((c) => c.skuNo === skuNo);
    if (item) {
      if (qty <= 0) db.cart = db.cart.filter((c) => c.skuNo !== skuNo);
      else item.qty = qty;
    }
    persist();
    return delay([...db.cart]);
  },

  async cartRemove(skuNos) {
    db.cart = db.cart.filter((c) => !skuNos.includes(c.skuNo));
    persist();
    return delay([...db.cart]);
  },

  // ---------------------------------------------------------------- 交易
  async createOrder(req: CreateOrderReq) {
    // 幂等：同一 key 重复提交返回同一单
    const dup = db.orders.find((o) => o.idempotencyKey === req.idempotencyKey);
    if (dup) return delay(dup);

    /*
     * 上门预约的两道闸，**与后端逐条同形**。
     *
     * mock 放行而后端拒收，是最坏的一种不一致：本地怎么点都对，
     * 一连真后端就拿到一个说不清的错误，而那时候没人会想到是 mock 太宽松。
     */
    if (req.fulfillment === FULFILLMENT.APPOINTMENT) {
      if (!req.appointmentAt || req.appointmentAt <= Date.now()) {
        throw new Error("请选择上门时段");
      }
      if (!req.addressId) throw new Error("上门服务需要收货地址");
    }

    const items: OrderItem[] = req.items.map((it) => {
      const seed = findGoodsSeed(it.goodsNo);
      const g = toGoods(seed);
      const sku = g.skus.find((s) => s.skuNo === it.skuNo);
      const rawSku = seed.skus.find((s) => s.skuNo === it.skuNo);
      if (!sku || !rawSku) throw new Error("规格不存在");
      if (g.cutoffAt && Date.now() > g.cutoffAt) throw new Error(`「${g.title}」已过截单时间`);
      if (rawSku.stock < it.qty) throw new Error(`「${g.title}」库存不足`);
      rawSku.stock -= it.qty; // 锁库（改种子，重开可读回）
      return {
        goodsNo: g.goodsNo,
        merchantNo: g.merchant.merchantNo,
        skuNo: sku.skuNo,
        title: g.title,
        cover: g.cover,
        spec: sku.spec,
        price: sku.price,
        qty: it.qty,
        type: g.type,
        nominalGram: sku.nominalGram,
        weighed: g.weighed,
        points: g.points,
      };
    });

    // 买赠：赠品作为价格为 0 的独立行，不参与计价，履约时随单发出
    const giftItems: OrderItem[] = [];
    for (const it of items) {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      const n = giftQtyFor(buyNGetM(g.promotions), it.qty);
      if (n > 0) giftItems.push({ ...it, price: 0, qty: n, isGift: true });
    }
    items.push(...giftItems);

    if (!items.length) throw new Error("订单商品为空");

    const couponSeed = db.couponSeeds.find((c) => c.couponNo === req.couponNo);
    const coupon: Coupon | undefined = couponSeed
      ? { ...couponSeed, title: pick(couponSeed.title), scopeDesc: pick(couponSeed.scopeDesc) }
      : undefined;

    // 用户可用积分不能超过账户余额 —— 这条必须在服务端校验，端上传什么都不能信
    const balance = pointBalance(db.points);
    const wantPoints = Math.max(0, Math.min(balance, req.usePoints ?? 0));

    const plan = fulfillmentFor(req.fulfillment).plan({
      pickupNo: req.pickupNo,
      communities: allCommunitySeeds().map(toCommunity),
      appointmentAt: req.appointmentAt,
    });

    /**
     * **按商家拆单**（E3）。购物车跨商家时拆成多笔子订单，一单只属于一个商家。
     *
     * 为什么必须拆：分账以子订单为单位（ADR-002 §5）——
     * 一笔钱要分给几家、各分多少，不拆就没有承载的单据；
     * 退款回退分账、履约服务费归属、商家看自己的单，全都依赖这个粒度。
     *
     * 用户感知不变：同一次结算的子订单共享一个**支付组号**，一次付掉整组。
     */
    const byMerchant = new Map<string, OrderItem[]>();
    for (const it of items) {
      byMerchant.set(it.merchantNo, [...(byMerchant.get(it.merchantNo) ?? []), it]);
    }

    const payGroupNo = nextNo("PG");
    const created: Order[] = [];

    for (const [merchantNo, subItems] of byMerchant) {
      const priced = subItems.filter((it) => !it.isGift);
      const head = priced[0] ?? subItems[0]!;
      // 优惠只作用在**第一笔**子订单上：券与积分是整单概念，
      // 按商家摊分需要业务口径（哪家承担、怎么摊），未定之前不臆造 —— 见 M4/B10
      const isFirst = created.length === 0;
      const amount = pricingFor(head.type).estimate(subItems, {
        fulfillment: req.fulfillment,
        currency: currentCurrency(),
        coupon: isFirst ? coupon : undefined,
        usePoints: isFirst ? wantPoints : 0,
        earnPoints: earnPointsFor(subItems),
      });

      const order: Order = {
        orderNo: nextNo("SO"),
        status: "WAIT_PAY",
        fulfillment: req.fulfillment,
        items: subItems,
        amount,
        pickupNo: plan.pickupNo,
        pickupName: plan.pickupName,
        appointmentAt: plan.appointmentAt,
        createdAt: Date.now(),
        payDeadlineAt: Date.now() + TRADE_RULES.payTimeoutMinutes * 60_000,
        timeline: [{ status: "WAIT_PAY", label: "已下单，待支付", at: Date.now() }],
        // 幂等 key 只挂在首单上：重复提交时靠它命中，返回同一组
        idempotencyKey: isFirst ? req.idempotencyKey : undefined,
        groupNo: req.groupNo,
        merchantNo,
        merchantName: merchantBrief(merchantNo).name,
        payGroupNo,
        trafficSource: db.user.merchantNo === merchantNo ? "MERCHANT_OWNED" : "PLATFORM",
      };

      // 抵扣的积分**下单即扣**（不是支付后）：不扣的话用户能同时下多单花同一笔积分
      if (amount.pointsUsed > 0) {
        pushPoint(db.points, "USE", -amount.pointsUsed, "下单抵扣", order.orderNo);
      }
      db.orders.unshift(order);
      created.push(order);
    }

    // 下单成功即从购物车移除这些 sku（赠品行不在购物车里，跳过）
    const orderedSkus = new Set(req.items.map((it) => it.skuNo));
    db.cart = db.cart.filter((c) => !orderedSkus.has(c.skuNo));
    persist();
    return delay(created[0]!);
  },

  async payOrder(orderNo) {
    const target = findOrder(orderNo);

    /**
     * **一次支付付掉整个支付组**（E3 拆单的另一半）。
     *
     * 拆单是资金侧的需要（分账以子订单为单位），但用户感知必须还是「买了一次」——
     * 只把点进来的那一单置为已支付，用户会在订单列表里看到「付了一单还剩一单」，
     * 而他明明只付了一次钱。
     */
    const group = target.payGroupNo
      ? db.orders.filter((o) => o.payGroupNo === target.payGroupNo)
      : [target];

    for (const o of group) {
      if (o.status !== "WAIT_PAY") continue; // 组内已处理过的跳过，重复点支付不报错
      assertTransition(o.status, "PAID");
      o.status = "PAID";
      o.amount.paidMinor = o.amount.payableMinor;
      pushTimeline(o, "支付成功");

      const strategy = fulfillmentFor(o.fulfillment);

      // 虚拟商品 / 卡券：支付成功即发放，不经备货，直接完成
      if (strategy.instant) {
        o.redeemCode = strategy.issueCode();
        o.items
          .filter((it) => it.type === CATEGORY_TYPE.CARD)
          .forEach((it) => issueCard(o, it));
        assertTransition(o.status, "COMPLETED");
        o.status = "COMPLETED";
        pushTimeline(o, "已发放");
        grantPointsOnComplete(o);
        continue;
      }

      // 没有独立的备货态：付款后就是 PAID（待发货），与后端一致
      assertTransition(o.status, "PAID");
      o.status = "PAID";
      o.verifyCode = strategy.issueCode();
      pushTimeline(o, "商家备货中");
    }

    // 消息按「一次结算」发一条，不是每个子订单发一条 —— 拆单是内部实现，不该泄漏成消息轰炸
    const titles = group
      .flatMap((o) => o.items.filter((i) => !i.isGift).map((i) => i.title))
      .join("、");
    pushMessage(
      "TRADE",
      group.length > 1 ? `支付成功，${group.length} 家商家备货中` : "支付成功，商家备货中",
      `${titles} 到货后会通知你`,
      `/pages/order/index?orderNo=${target.orderNo}`,
    );

    persist();
    return delay(target);
  },

  async orderList(q: PageQuery & { status?: string; fulfillments?: string[] }) {
    // 两个条件正交，各筛各的 —— 与真实后端同形（页签是谓词，不是状态值）
    const want = q.fulfillments?.length ? new Set(q.fulfillments) : null;
    const list = db.orders.filter(
      (o) => (!q.status || o.status === q.status) && (!want || want.has(o.fulfillment)),
    );
    return delay(paginate(list, q.page, q.size));
  },

  async applyInvoice(req) {
    const order = db.orders.find((x) => x.orderNo === req.orderNo);
    if (!order) throw new Error("订单不存在");
    // mock 也照真实边界来：未支付的单不能开票。恒成功的话，
    // 「什么时候该出现这个入口」这段永远走不到
    if (order.status === "WAIT_PAY" || order.status === "CANCELLED") {
      throw new Error("这笔订单还没有成交，无法开票");
    }
    if (req.titleType === "COMPANY" && !req.taxNo?.trim()) {
      throw new Error("单位抬头需要税号");
    }
    const exist = db.invoiceRequests.find((x) => x.orderNo === req.orderNo);
    if (exist && exist.status !== "REJECTED") throw new Error("这笔订单已经申请过发票");
    if (exist) {
      // 被驳回后改抬头重提：**改同一条，不插新的** —— 与后端一致，
      // 插新的话同一订单会有两条，运营分不清该开哪张
      Object.assign(exist, { ...req, status: "REQUESTED", rejectReason: undefined });
      persist();
      return delay({ ...exist });
    }
    const r: InvoiceRequest = {
      requestNo: `INV${Date.now()}`,
      orderNo: req.orderNo,
      titleType: req.titleType,
      title: req.title,
      taxNo: req.taxNo,
      email: req.email,
      // 开票金额 = **应付**，不是商品小计：运费与优惠都在里面。
      // 取错的话票面金额与消费者实付对不上，对方入账时会被退回
      amountMinor: order.amount.payableMinor,
      status: "REQUESTED",
      createdAt: Date.now(),
    };
    db.invoiceRequests.push(r);
    persist();
    return delay({ ...r });
  },

  async myInvoices() {
    return delay(db.invoiceRequests.map((x) => ({ ...x })));
  },

  async invoiceOfOrder(orderNo) {
    const r = db.invoiceRequests.find((x) => x.orderNo === orderNo);
    // 没申请过返回 null 而不是抛错：那是常态不是错误
    return delay(r ? { ...r } : null);
  },

  async orderDetail(orderNo) {
    const o = findOrder(orderNo);
    /*
     * 同支付组的兄弟单一起带上（对齐后端 OrderVO 的支付视角）。
     * 收银台靠它显示「本次付款覆盖 N 笔订单」——
     * mock 不给的话，那一屏在 mock 下永远是哑的，而它恰恰是最该被看见的一屏。
     *
     * 只在**确实跨了商家**时带：单商家时 subOrders 等于把自己抄一遍，端上也不渲染。
     */
    const siblings = o.payGroupNo
      ? db.orders.filter((x) => x.payGroupNo === o.payGroupNo)
      : [];
    return delay(siblings.length > 1 ? { ...o, subOrders: siblings } : o);
  },

  async cancelOrder(orderNo) {
    const o = findOrder(orderNo);
    assertTransition(o.status, "CANCELLED");
    o.status = "CANCELLED";
    // 释放锁库
    o.items.forEach((it) => {
      const sku = findGoodsSeed(it.goodsNo).skus.find((s) => s.skuNo === it.skuNo);
      if (sku) sku.stock += it.qty;
    });
    if (o.amount.pointsUsed > 0) {
      pushPoint(db.points, "REFUND", o.amount.pointsUsed, "订单取消返还", o.orderNo);
    }
    pushTimeline(o, "订单已取消");
    persist();
    return delay(o);
  },

  // ---------------------------------------------------------------- 售后
  async applyAfterSale(orderNo, reason, images, type = "REFUND_ONLY") {
    const o = findOrder(orderNo);
    /*
     * **订单状态不动。** 售后是挂在订单上的另一张单，两者并存 ——
     * 一个「已完成」的订单照样能申请售后，把它改成「退款中」就丢失了
     * 「货其实已经收到了」这个事实，也让订单列表的其它页签少一条。
     */
    o.afterSale = {
      afterSaleNo: nextNo("AS"),
      // mock 里 Order 就是子订单，两个号取同一个值
      subOrderNo: o.orderNo,
      orderNo: o.orderNo,
      type,
      status: "APPLIED",
      reason,
      images,
      // 整单退：mock 不做部分退款
      refundMinor: o.amount.paidMinor || o.amount.payableMinor,
      instant: type === "REFUND_ONLY"
        && (o.amount.paidMinor || o.amount.payableMinor) <= TRADE_RULES.instantRefundMaxMinor,
      updatedAt: Date.now(),
    };
    pushTimeline(o, `已申请${type === "RETURN_REFUND" ? "退货退款" : "仅退款"}：${reason}`);

    /**
     * 极速退：小额自动通过。**只对「仅退款」生效** ——
     * 退货退款要等货回来才能退，自动退等于货款两失。
     */
    if (type === "REFUND_ONLY" && o.amount.paidMinor <= TRADE_RULES.instantRefundMaxMinor) {
      settleRefund(o, "极速退款已到账");
    }
    persist();
    // 返回售后单本身 —— 与后端同形（端上拿它刷新，不是拿它替换订单）
    return delay(o.afterSale!);
  },

  async afterSaleReasons() {
    // 与后端 AfterSaleServiceImpl.REASONS 同一份清单（码，不是文案）
    return delay<AfterSaleReason[]>([
      "NOT_WANTED", "DAMAGED", "MISSING", "WRONG_ITEM", "QUALITY", "EXPIRED", "OTHER",
    ]);
  },

  async orderPreview(req) {
    /*
     * mock 里没有服务端活动，沿用与下单同一套定价策略 —— 两者算出同一个数才是 mock 的价值。
     * **不扣库存**：预览是只读的，用户会在结算页反复改地址与履约方式。
     */
    const items: OrderItem[] = req.items.map((it) => {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      const sku = g.skus.find((s) => s.skuNo === it.skuNo);
      if (!sku) throw new Error("规格不存在");
      return {
        goodsNo: g.goodsNo, merchantNo: g.merchant.merchantNo, skuNo: sku.skuNo,
        title: g.title, cover: g.cover, spec: sku.spec, price: sku.price, qty: it.qty,
        type: g.type, nominalGram: sku.nominalGram, weighed: g.weighed, points: g.points,
      };
    });
    if (!items.length) throw new Error("订单商品为空");
    const couponSeed = db.couponSeeds.find((c) => c.couponNo === req.couponNo);
    const coupon: Coupon | undefined = couponSeed
      ? { ...couponSeed, title: pick(couponSeed.title), scopeDesc: pick(couponSeed.scopeDesc) }
      : undefined;
    const amount = pricingFor(items[0]!.type).estimate(items, {
      fulfillment: req.fulfillment,
      currency: currentCurrency(),
      coupon,
      usePoints: Math.max(0, Math.min(pointBalance(db.points), req.usePoints ?? 0)),
      earnPoints: 0,
    });
    return delay({ amount, items });
  },

  /**
   * 结算页能力提示。
   *
   * mock 里造两种小微形态：**不可开票**与**额度将超**。
   * 造成「全都正常」的话这块提示永远不出现，等于没做 —— mock 的价值恰恰是
   * 让人在开发时就看见那几条提示长什么样。
   */
  async orderCapability(req) {
    const seen = new Map<string, { name: string; micro: boolean }>();
    for (const it of req.items) {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      // 约定：mock 里 merchantNo 以 M9 开头的当作小微，用来演示提示
      seen.set(g.merchant.merchantNo, {
        name: g.merchant.name,
        micro: g.merchant.merchantNo.startsWith("M9"),
      });
    }
    const merchants = [...seen.entries()].map(([merchantNo, m]) => ({
      merchantNo,
      merchantName: m.name,
      invoiceCapable: !m.micro,
      // 小微通常没有 H5/APP —— 混合购物车里有一件小微的货，整单就只剩 JSAPI
      payMethods: m.micro ? ["JSAPI"] : ["JSAPI", "H5", "APP"],
      quotaExhausted: false,
      quotaWouldExceed: false,
    }));
    // 与后端同口径：一个商家都没配时返回 null（未配置），不是空数组（无交集）
    const configured = merchants.filter((m) => m.payMethods.length);
    const usable = configured.length
      ? configured.map((m) => m.payMethods).reduce((a, b) => a.filter((x) => b.includes(x)))
      : null;
    /*
     * 支付方式（线上/当面）。mock 里的约定：**自提与到店核销给当面付**，
     * 快递不给 —— 与后端那道闸同口径（货已寄出，没有当面收款的那一刻）。
     *
     * 不是「全都给」：开发期看不见「这一单只能线上付」长什么样的话，
     * 那个分支等于没做。这与上面造两种小微形态是同一个理由。
     */
    const offlineOk = req.fulfillment === FULFILLMENT.PICKUP
      || req.fulfillment === FULFILLMENT.STORE_VERIFY
      || req.fulfillment === FULFILLMENT.DELIVERY;
    return delay({
      usablePayMethods: usable,
      anyNotInvoiceCapable: merchants.some((m) => !m.invoiceCapable),
      merchants,
      usablePayModes: offlineOk ? [PAY_MODE.ONLINE, PAY_MODE.OFFLINE] : [PAY_MODE.ONLINE],
    });
  },

  async afterSaleList() {
    // 售后是独立资源：从订单上摘出来，而不是拿订单状态冒充
    return delay(db.orders.filter((o) => o.afterSale).map((o) => o.afterSale!));
  },

  async fillReturnExpress(afterSaleNo, expressNo) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (as.type !== "RETURN_REFUND") throw new Error("该售后单不是退货退款");
    // 只有商家同意之后才谈得上寄回 —— 没同意就寄，货可能被拒收。
    // 后端同意即进 REFUNDING，没有独立的「等寄回」「已收货」两态：
    // 退货物流走 expressNo 字段，不是状态（见 AfterSaleStatus 的说明）
    if (as.status !== "REFUNDING") throw new Error("商家同意后才能填写退货单号");
    if (!expressNo.trim()) throw new Error("请填写退货运单号");
    as.returnExpressNo = expressNo.trim();
    as.updatedAt = Date.now();
    pushTimeline(o, `已寄回，运单号 ${as.returnExpressNo}`);
    persist();
    return delay(as);
  },

  async raiseDispute(afterSaleNo, reason) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    // **只有被驳回才谈得上申诉** —— 商家还没处理就上升，等于跳过协商
    if (as.status !== "REJECTED") throw new Error("商家驳回后才能申请平台介入");
    as.status = "ARBITRATING";
    as.disputeReason = reason;
    as.updatedAt = Date.now();
    pushTimeline(o, "已申请平台介入");
    pushMessage(
      "TRADE",
      "平台介入已受理",
      "客服会在 1 个工作日内联系双方核实",
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(as);
  },

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

  // ---------------------------------------------------------------- 消息
  async messageList() {
    return delay([...db.messages].sort((a, b) => b.at - a.at));
  },

  async readMessage(messageNo) {
    const m = db.messages.find((x) => x.messageNo === messageNo);
    if (m) m.read = true;
    persist();
    return delay([...db.messages]);
  },

  async subscribeReport() {
    // mock 世界没有微信授权额度这回事，收下即可
    return delay(undefined);
  },

  async unreadMessages() {
    return delay(db.messages.filter((m) => !m.read).length);
  },

  // mock 世界没有真设备（H5 下 getPushDevice 恒为 null，这两个压根不会被调到）
  async registerPushToken() {
    return delay(undefined);
  },

  async unregisterPushToken() {
    return delay(undefined);
  },

  async readAllMessages() {
    db.messages.forEach((m) => (m.read = true));
    persist();
    return delay([...db.messages]);
  },

  // ---------------------------------------------------------------- 卡包
  async myCards() {
    // 标题按当前语言重算（同购物车）
    db.cards = db.cards.map((c) => ({
      ...c,
      title: toGoods(findGoodsSeed(c.goodsNo)).title,
    }));
    return delay([...db.cards]);
  },

  // ---------------------------------------------------------------- 团长

  /** 团长视角：本自提点的订单。真实后端按 pickupNo + 团长归属过滤 */

  /**
   * 分拣单：按 SKU 汇总。
   * 团长到货那天照着这个点数 —— 所以是「商品维度」而不是「订单维度」，
   * 按订单列会让人在几十个包裹之间反复翻找同一个商品。
   */

  /** 到货：批量把备货中的订单推到「已到自提点」，用户此时收到到货通知 */
};
