// B 端演示订单种子。
//
// 为什么单独放在 b-app 而不是共享 db：C 端的订单是用户自己下出来的，一进来就有一堆
// 「我的订单」反而是错的。B 端相反 —— 商家一进来订单列表、核销台、分拣单全空，
// 三个页面都验证不了。所以只在 **B 端** 且 db.orders 为空时补一批。
//
// ⚠️ 这是演示数据，接真后端（VITE_USE_MOCK=0）后整个文件不参与。
import { allGoods, db, merchantBrief, nextNo, persist } from "@shared/mock/db";
import { FULFILLMENT } from "@shared/utils/constants";
import { currentCurrency } from "@shared/utils/money";
import type { Order, OrderItem, OrderStatus, FulfillmentType } from "@shared/types";

const MIN = 60_000;

function itemOf(goodsNo: string, qty: number): OrderItem | null {
  const g = allGoods().find((x) => x.goodsNo === goodsNo);
  const sku = g?.skus[0];
  if (!g || !sku) return null;
  return {
    goodsNo: g.goodsNo,
    merchantNo: g.merchant.merchantNo,
    skuNo: sku.skuNo,
    title: g.title,
    cover: g.cover,
    spec: sku.spec,
    price: sku.price,
    qty,
    type: g.type,
  };
}

function build(
  status: OrderStatus,
  fulfillment: FulfillmentType,
  buyer: string,
  items: OrderItem[],
  minutesAgo: number,
  trafficSource: "MERCHANT_OWNED" | "PLATFORM",
): Order {
  const goodsMinor = items.reduce((s, it) => s + it.price * it.qty, 0);
  const at = Date.now() - minutesAgo * MIN;
  const pickup = db.communitySeeds[0]?.pickups[0];
  return {
    orderNo: nextNo("SO"),
    status,
    fulfillment,
    items,
    amount: {
      goodsMinor,
      freightMinor: 0,
      discountMinor: 0,
      payableMinor: goodsMinor,
      paidMinor: goodsMinor,
      pointsDeductMinor: 0,
      pointsUsed: 0,
      pointsEarn: 0,
      currency: currentCurrency(),
    },
    // 自提单必须有取货码，否则核销台没东西可核
    verifyCode:
      fulfillment === FULFILLMENT.PICKUP
        ? String(1000 + Math.floor((at / 1000) % 9000))
        : undefined,
    pickupNo: fulfillment === FULFILLMENT.PICKUP ? pickup?.pickupNo : undefined,
    pickupName: fulfillment === FULFILLMENT.PICKUP ? "阳光里 3 幢自提点" : undefined,
    createdAt: at,
    timeline: [{ status: "PAID", label: "已支付", at }],
    buyerNickname: buyer,
    /*
     * 收件人快照。**自提单没有**（货在自提点，不送）。
     *
     * 手机号跟着真实口径走：自送给完整号（配送员站在楼下要打电话），
     * 快递给脱敏号 —— mock 里也照这个分档，否则页面在 mock 下看着是对的，
     * 接上真后端才发现「配送员点电话没反应」。
     */
    receiver:
      fulfillment === FULFILLMENT.PICKUP
        ? undefined
        : {
            name: buyer,
            phone: fulfillment === FULFILLMENT.DELIVERY ? "13800001234" : "****1234",
            address: "杭州市西湖区文三路 100 号 3 幢 502",
          },
    trafficSource,
    // 拆单后一单只属于一个商家（E3）—— 演示单也照这个粒度造，
    // 否则 B 端会走进「兼容历史单」的回退分支，验证不到真实路径
    merchantNo: items[0]?.merchantNo,
  };
}

/**
 * 演示商家。**没有它，B 端第一次打开是一个空壳** ——
 * 工作台「还没有开店」、订单/商品/核销/分拣全空，看着像整个端坏了。
 * 以前不需要是因为开发机上留着历史入驻数据；换了存储命名空间之后，
 * 每个新浏览器都会撞上，这才暴露出来：**mock 从来没有「已开店」的初始状态**。
 *
 * 选 M002（阿明果蔬合作社）：它在种子里有商品、有评价、有自提点角色，
 * 三条履约线都能跑通；M001 是平台自营，不代表普通店主的视角。
 *
 * 只在 mock 下生效；已经入驻过（有 merchantNo）就原样不动，不覆盖店主自己的资料。
 */
export function ensureDemoMerchant(): void {
  if (db.merchant.merchantNo) return;
  const seed = merchantBrief("M002");
  Object.assign(db.merchant, {
    merchantNo: seed.merchantNo,
    name: seed.name,
    logo: seed.logo,
    status: "ACTIVE",
    subject: "INDIVIDUAL_BIZ",
    phone: "13800138000",
    isPickupPoint: true,
  });
  // 落盘：不存的话每次启动都重新种，店主改过的资料会被盖掉，
  // 而且「刷新一下数据就回到初始」本身就不像真后端
  persist();
}

/** db.orders 为空时补一批演示单。已有数据则原样不动，不覆盖店主的操作结果。 */
export function ensureDemoOrders(): void {
  // 判据是「本店有没有**待办**单」，不是「db 里有没有单」——
  // 共享种子里本来就带着几条 C 端的已完成单，用 `db.orders.length` 当判据会被它们挡住，
  // 结果工作台六个待办数字全是 0，看着像没有生意（这正是 B 端「打开像空壳」的第二层原因）
  const PENDING = ["PAID", "ARRIVED", "SHIPPED"];
  const hasPending = db.orders.some(
    (o) =>
      o.merchantNo === db.merchant.merchantNo &&
      (PENDING.includes(o.status) || o.afterSale),
  );
  if (hasPending) return;
  // 未入驻时不补：此时还不知道是哪家店，补出来的单会挂在别人名下，入驻后订单列表反而是空的
  if (!db.merchant.merchantNo) return;

  const pool = allGoods()
    .filter((g) => g.merchant.merchantNo === db.merchant.merchantNo)
    .slice(0, 3);
  if (!pool.length) return;

  const pick = (i: number, qty: number) => itemOf(pool[i % pool.length]!.goodsNo, qty);
  const rows: Order[] = [];

  const a = pick(0, 2);
  const b = pick(1, 1);
  const c = pick(2, 3);

  // 覆盖三条履约线各自的待办态，让工作台的数字不是全 0
  if (a) rows.push(build("ARRIVED", FULFILLMENT.PICKUP, "邻居小张", [a], 180, "MERCHANT_OWNED"));
  if (b) rows.push(build("PAID", FULFILLMENT.PICKUP, "李阿姨", [b], 120, "MERCHANT_OWNED"));
  if (c) rows.push(build("PAID", FULFILLMENT.DELIVERY, "王先生", [c], 60, "PLATFORM"));
  if (a) rows.push(build("PAID", FULFILLMENT.EXPRESS, "陈小姐", [a], 40, "PLATFORM"));
  /*
   * 一条待处理售后，否则售后页永远是空的，同意/驳回两条分支都验证不了。
   * **订单状态是 COMPLETED**：售后挂在订单上，两者并存 ——
   * 此前这里造的是 status="REFUNDING" 的单，那个订单状态后端根本不存在。
   */
  if (b) {
    const withAfterSale = build("COMPLETED", FULFILLMENT.PICKUP, "赵大爷", [b], 220, "MERCHANT_OWNED");
    withAfterSale.afterSale = {
      afterSaleNo: nextNo("AS"),
      subOrderNo: withAfterSale.orderNo,
      orderNo: withAfterSale.orderNo,
      type: "REFUND_ONLY",
      status: "APPLIED",
      reason: "QUALITY",
      images: [],
      updatedAt: Date.now(),
    };
    rows.push(withAfterSale);
  }

  // 历史单。**没有这几条，「我的客户」页就是废的** ——
  // 全是今天的单会让复购率恒等于 0、沉默客户恒等于 0，
  // 而那两个正是这页仅有的两个信号。
  const DAYS = 24 * 60;
  // 邻居小张：老客，三个月来陆续买过几次 → 撑起复购率
  if (a) rows.push(build("COMPLETED", FULFILLMENT.PICKUP, "邻居小张", [a], 9 * DAYS, "MERCHANT_OWNED"));
  if (b) rows.push(build("COMPLETED", FULFILLMENT.PICKUP, "邻居小张", [b], 26 * DAYS, "MERCHANT_OWNED"));
  // 孙奶奶：买过两次、20 天没来 → 沉默客户。
  // 沉默样本必须是**今天没有单**的人 —— 李阿姨今天有待分拣的单，
  // 拿她当样本的话 lastOrderAt 是今天，永远沉默不了
  if (b) rows.push(build("COMPLETED", FULFILLMENT.PICKUP, "孙奶奶", [b], 20 * DAYS, "MERCHANT_OWNED"));
  if (a) rows.push(build("COMPLETED", FULFILLMENT.PICKUP, "孙奶奶", [a], 34 * DAYS, "MERCHANT_OWNED"));

  db.orders.push(...rows);
  persist();
}
