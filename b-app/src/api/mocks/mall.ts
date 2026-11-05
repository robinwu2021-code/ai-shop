// 平台商城的本机假数据（mock 模式看界面用）。规则照真实后端：报价冻结金额、下单后发指引、
// 申报只是「我付了」——状态停在 SUBMITTED，等运营核款；同一单已有待审申报时不能再报
import type {
  MallApi, MallOfflineClaim, MallOfflineInstructions, MallOrderDetail, MallQuote, MallStorefront,
} from "../mall";
import { currentStoreNo } from "./_shared";

const TWD = (minor: number) => ({ currency: "TWD", minor: String(minor) });
const now = () => new Date().toISOString();

const storefront: MallStorefront = {
  businessTypeCode: "RETAIL",
  currency: "TWD",
  canProcure: true,
  products: [
    {
      product: { productNo: "P-PRO", kind: "PLAN", name: "零售 PRO 方案", description: "收银、会员与报表全套，一年期" },
      skus: [{ sku: { skuNo: "PRO-365", skuVersionNo: "PRO-365-v1", title: "PRO · 365 天", price: TWD(699000), listPrice: TWD(799000),
        components: [{ kind: "PLAN", planVersionNo: "PRO-v1", periodDays: 365, unitCount: 1 }] } }],
    },
    {
      product: { productNo: "P-POS", kind: "HARDWARE", name: "收银机 POS-1", description: "含小票打印机" },
      skus: [{ sku: { skuNo: "POS-1", skuVersionNo: "POS-1-v1", title: "POS-1 收银机", price: TWD(1299000),
        components: [{ kind: "HARDWARE", hardwareSkuNo: "HW-POS-1", unitCount: 1 }] } }],
    },
    {
      product: { productNo: "P-KIT", kind: "BUNDLE", name: "开店套装", description: "PRO 一年 + POS-1 收银机" },
      skus: [{ sku: { skuNo: "KIT-1", skuVersionNo: "KIT-1-v1", title: "PRO 365 天 + POS-1", price: TWD(1799000),
        components: [{ kind: "PLAN", planVersionNo: "PRO-v1", periodDays: 365, unitCount: 1 },
          { kind: "HARDWARE", hardwareSkuNo: "HW-POS-1", unitCount: 1 }] } }],
    },
  ],
};

// 一律按「门店:单号」存：切店后看不到别家店的单，与后端按路径门店号校验归属一致
const quotes = new Map<string, MallQuote>();
const orders = new Map<string, MallOrderDetail>();
const instructions = new Map<string, MallOfflineInstructions>();
const claims = new Map<string, MallOfflineClaim[]>();
const k = (store: string, no: string) => `${store}:${no}`;

function sku(versionNo: string) {
  for (const p of storefront.products ?? []) for (const s of p.skus ?? []) if (s.sku?.skuVersionNo === versionNo) return s.sku;
  throw new Error("NOT_FOUND");
}

export const mallMock: MallApi = {
  async mMallStorefront(storeNo) {
    return { ...storefront, store: { tenantNo: "MAIN", storeNo: storeNo || currentStoreNo() } };
  },
  async mMallOfflineQuote(storeNo, body) {
    const store = storeNo || currentStoreNo();
    const s = sku(body.lines[0]!.skuVersionNo);
    const quoteNo = "Q" + Date.now();
    const q: MallQuote = {
      quoteNo, total: s.price, credit: TWD(0), payable: s.price,
      components: (s.components ?? []).map((c, i) => ({ componentNo: `C${i + 1}`, kind: c.kind, cycleDays: c.periodDays ?? undefined,
        quantity: c.unitCount, dealAmount: i === 0 ? s.price : TWD(0) })),
      expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    };
    quotes.set(k(store, quoteNo), q);
    return q;
  },
  async mMallPlaceOrder(storeNo, body) {
    const store = storeNo || currentStoreNo();
    const q = quotes.get(k(store, body.quoteNo));
    if (!q) throw new Error("QUOTE_EXPIRED");
    const orderNo = "MO" + Date.now();
    const order = { orderNo, status: "AWAITING_PAYMENT" as const, payable: q.payable, components: q.components, createdAt: now() };
    orders.set(k(store, orderNo), { summary: { order, paymentStatus: "UNPAID", fulfillmentStatus: "PENDING", refundStatus: "NONE" },
      quote: q, shippingAddress: body.shippingAddress, shipments: [] });
    return order;
  },
  async mMallOrders(storeNo) {
    const prefix = `${storeNo || currentStoreNo()}:`;
    return [...orders.entries()].filter(([key]) => key.startsWith(prefix)).map(([, d]) => d.summary!).reverse();
  },
  async mMallOrder(storeNo, orderNo) {
    const d = orders.get(k(storeNo || currentStoreNo(), orderNo));
    if (!d) throw new Error("NOT_FOUND");
    return d;
  },
  async mMallIssueInstructions(storeNo, orderNo) {
    const key = k(storeNo || currentStoreNo(), orderNo);
    const d = orders.get(key);
    if (!d) throw new Error("NOT_FOUND");
    const existing = instructions.get(key);
    if (existing) return existing;
    const v: MallOfflineInstructions = {
      orderNo, payable: d.summary!.order!.payable!, paymentDueAt: new Date(Date.now() + 86_400_000).toISOString(),
      profileNo: "TW-BANK", profileVersion: 1,
      instructions: { bankCode: "808", bankAccount: "0123-4567-8901", accountName: "邻里科技股份有限公司" },
    };
    instructions.set(key, v);
    return v;
  },
  async mMallInstructions(storeNo, orderNo) {
    const v = instructions.get(k(storeNo || currentStoreNo(), orderNo));
    if (!v) throw new Error("OFFLINE_INSTRUCTIONS_NOT_ISSUED");
    return v;
  },
  async mMallReserveEvidence(storeNo, orderNo) {
    if (!orders.has(k(storeNo || currentStoreNo(), orderNo))) throw new Error("NOT_FOUND");
    return { assetKey: "ev-" + Date.now() + Math.random().toString(36).slice(2, 6), purpose: "BUYER_RECEIPT" };
  },
  async mMallUploadEvidence(storeNo, orderNo, assetKey) {
    if (!orders.has(k(storeNo || currentStoreNo(), orderNo))) throw new Error("NOT_FOUND");
    return { assetKey };
  },
  async mMallSubmitClaim(storeNo, orderNo, body) {
    const key = k(storeNo || currentStoreNo(), orderNo);
    const list = claims.get(key) ?? [];
    if (list.some((c) => c.status === "SUBMITTED" || c.status === "REVIEWING")) throw new Error("CLAIM_ALREADY_SUBMITTED");
    const c: MallOfflineClaim = { claimNo: body.claimNo, status: "SUBMITTED", declaredAmount: body.declaredAmount,
      declaredPaidAt: body.declaredPaidAt, submittedAt: now() };
    claims.set(key, [...list, c]);
    return c;
  },
  async mMallClaims(storeNo, orderNo) {
    return claims.get(k(storeNo || currentStoreNo(), orderNo)) ?? [];
  },
};
