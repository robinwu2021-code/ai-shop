// 平台商城的本机假数据（mock 模式看界面用）。规则照真实后端：报价冻结金额、下单后发指引、
// 申报只是「我付了」——状态停在 SUBMITTED，等运营核款；同一单已有待审申报时不能再报
import type {
  MallApi, MallOfflineClaim, MallOfflineInstructions, MallOrderDetail, MallQuote, MallStorefront,
} from "../mall";
import { currentStoreNo } from "./_shared";

const TWD = (minor: number) => ({ currency: "TWD", minor: String(minor) });
const now = () => new Date().toISOString();

const offlineReadiness = { available: true, paymentOptions: [{ channel: "OFFLINE", payMethod: "BANK_TRANSFER", currency: "TWD" }] };
const storefront: MallStorefront = {
  businessTypeCode: "RETAIL",
  currency: "TWD",
  canProcure: true,
  products: [
    {
      product: { productNo: "P-PRO", kind: "PLAN", name: "零售 PRO 方案", description: "收银、会员与报表全套，一年期" },
      skus: [{ offlineReadiness, sku: { skuNo: "PRO-365", skuVersionNo: "PRO-365-v1", title: "PRO · 365 天", price: TWD(699000), listPrice: TWD(799000),
        components: [{ kind: "PLAN", planVersionNo: "PRO-v1", periodDays: 365, unitCount: 1 }] } }],
    },
    {
      product: { productNo: "P-POS", kind: "HARDWARE", name: "收银机 POS-1", description: "含小票打印机" },
      skus: [{ offlineReadiness, sku: { skuNo: "POS-1", skuVersionNo: "POS-1-v1", title: "POS-1 收银机", maxPerOrder: 10, price: TWD(1299000),
        components: [{ kind: "HARDWARE", hardwareSkuNo: "HW-POS-1", unitCount: 1 }] } }],
    },
    {
      product: { productNo: "P-KIT", kind: "BUNDLE", name: "开店套装", description: "PRO 一年 + POS-1 收银机" },
      skus: [{ offlineReadiness, sku: { skuNo: "KIT-1", skuVersionNo: "KIT-1-v1", title: "PRO 365 天 + POS-1", price: TWD(1799000),
        components: [{ kind: "PLAN", planVersionNo: "PRO-v1", periodDays: 365, unitCount: 1 },
          { kind: "HARDWARE", hardwareSkuNo: "HW-POS-1", unitCount: 1 }] } }],
    },
  ],
};

// 一律按「门店:单号」存：切店后看不到别家店的单，与后端按路径门店号校验归属一致
const quotes = new Map<string, { quote: MallQuote; lines: MallOrderDetail["lines"] }>();
let serial = 0;
const placements = new Map<string, { request: string; orderNo: string }>();
const usedQuotes = new Set<string>();
const orders = new Map<string, MallOrderDetail>();
const instructions = new Map<string, MallOfflineInstructions>();
const evidence = new Map<string, { order: string; uploaded: boolean }>();
const claims = new Map<string, MallOfflineClaim[]>();
const k = (store: string, no: string) => `${store}:${no}`;

function sku(versionNo: string) {
  for (const p of storefront.products ?? []) for (const s of p.skus ?? []) if (s.sku?.skuVersionNo === versionNo) return { productNo: p.product!.productNo!, s: s.sku };
  throw new Error("NOT_FOUND");
}

export const mallMock: MallApi = {
  async mMallStorefront(storeNo) {
    return { ...storefront, store: { tenantNo: "MAIN", storeNo: storeNo || currentStoreNo() } };
  },
  async mMallOfflineQuote(storeNo, body) {
    const store = storeNo || currentStoreNo();
    if (body.lines.length !== 1 || body.currency !== storefront.currency) throw new Error("INVALID_REQUEST");
    const { productNo, s } = sku(body.lines[0]!.skuVersionNo);
    const count = body.lines[0]!.quantity;
    if (!Number.isSafeInteger(count) || count < 1 || count > (s.maxPerOrder ?? 1)) throw new Error("INVALID_REQUEST");
    const total = { ...s.price!, minor: String(BigInt(s.price!.minor) * BigInt(count)) };
    const quoteNo = "Q" + ++serial;
    const q: MallQuote = {
      quoteNo, total, credit: TWD(0), payable: total,
      components: (s.components ?? []).map((c, i) => ({ componentNo: `C${i + 1}`, kind: c.kind, cycleDays: c.periodDays ?? undefined,
        quantity: (c.unitCount ?? 1) * count, dealAmount: i === 0 ? total : TWD(0) })),
      expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    };
    quotes.set(k(store, quoteNo), { quote: q, lines: [{ productNo, skuNo: s.skuNo!, skuVersionNo: s.skuVersionNo!, title: s.title!, quantity: count }] });
    return q;
  },
  async mMallPlaceOrder(storeNo, body) {
    const store = storeNo || currentStoreNo();
    const identity = k(store, body.idempotencyKey);
    const address = body.shippingAddress;
    const request = JSON.stringify([body.quoteNo, address && [address.recipient,address.phone,address.countryCode,address.region,address.city,address.addressLine,address.postalCode]]);
    const prior = placements.get(identity);
    if (prior) {
      if (prior.request !== request) throw new Error("IDEMPOTENCY_CONFLICT");
      return orders.get(k(store, prior.orderNo))!.summary!.order!;
    }
    const quoteKey = k(store, body.quoteNo);
    const frozen = quotes.get(quoteKey);
    const q = frozen?.quote;
    if (!q || Date.parse(q.expiresAt!) <= Date.now()) throw new Error("QUOTE_EXPIRED");
    if (usedQuotes.has(quoteKey)) throw new Error("QUOTE_USED");
    if (q.components?.some(c => c.kind === "HARDWARE") && !(address?.recipient && address.phone && address.countryCode && address.addressLine)) throw new Error("SHIPPING_REQUIRED");
    const orderNo = "MO" + ++serial;
    const order = { orderNo, store: { tenantNo: "MAIN", storeNo: store }, status: "AWAITING_PAYMENT" as const, payable: q.payable, components: q.components, createdAt: now() };
    orders.set(k(store, orderNo), { lines: frozen.lines, summary: { order, title: frozen.lines?.[0]?.title, paymentStatus: "UNPAID", fulfillmentStatus: "PENDING", refundStatus: "NONE" },
      quote: q, shippingAddress: body.shippingAddress, shipments: [] });
    placements.set(identity, { request, orderNo });
    usedQuotes.add(quoteKey);
    return order;
  },
  async mMallOrders(storeNo) {
    const prefix = `${storeNo || currentStoreNo()}:`;
    return [...orders.entries()].filter(([key]) => key.startsWith(prefix)).map(([, d]) => d.summary!).reverse();
  },
  // Local checkout stops at submitted payment. Only paid orders qualify for refunds.
  async mMallRefundPreview(storeNo, orderNo) {
    if (!orders.has(k(storeNo || currentStoreNo(), orderNo))) throw new Error("NOT_FOUND");
    throw new Error("INVALID_STATE");
  },
  async mMallRefund(storeNo, orderNo) {
    if (!orders.has(k(storeNo || currentStoreNo(), orderNo))) throw new Error("NOT_FOUND");
    throw new Error("INVALID_STATE");
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
      profileNo: "TW-BANK", profileVersion: 1, collectionMethod: "BANK_TRANSFER",
      instructions: { transferRemark: orderNo, bankCode: "808", bankAccount: "0123-4567-8901", accountName: "邻里科技股份有限公司" },
    };
    d.summary!.paymentStatus = "PAYING";
    instructions.set(key, v);
    return v;
  },
  async mMallInstructions(storeNo, orderNo) {
    const v = instructions.get(k(storeNo || currentStoreNo(), orderNo));
    if (!v) throw new Error("OFFLINE_INSTRUCTIONS_NOT_ISSUED");
    return v;
  },
  async mMallReserveEvidence(storeNo, orderNo) {
    const order = k(storeNo || currentStoreNo(), orderNo);
    if (!orders.has(order)) throw new Error("NOT_FOUND");
    const assetKey = `mall-private/${++serial}.jpg`;
    evidence.set(assetKey, { order, uploaded: false });
    return { assetKey, purpose: "BUYER_RECEIPT" };
  },
  async mMallUploadEvidence(storeNo, orderNo, assetKey) {
    const proof = evidence.get(assetKey);
    if (proof?.order !== k(storeNo || currentStoreNo(), orderNo)) throw new Error("EVIDENCE_FORBIDDEN");
    if (proof.uploaded) throw new Error("EVIDENCE_ALREADY_STORED");
    proof.uploaded = true;
    return { assetKey };
  },
  async mMallSubmitClaim(storeNo, orderNo, body) {
    const key = k(storeNo || currentStoreNo(), orderNo);
    if (!orders.has(key)) throw new Error("NOT_FOUND");
    if (!body.evidenceKeys.length || body.evidenceKeys.some(k => {
      const proof = evidence.get(k);
      return proof?.order !== key || !proof.uploaded;
    })) throw new Error("EVIDENCE_UNAVAILABLE");
    const list = claims.get(key) ?? [];
    const prior = list.find(c => c.claimNo === body.claimNo);
    if (prior) {
      if (prior.bankReference !== body.bankReference || prior.declaredPaidAt !== body.declaredPaidAt
        || prior.declaredAmount.currency !== body.declaredAmount.currency || prior.declaredAmount.minor !== body.declaredAmount.minor
        || JSON.stringify(prior.evidenceKeys) !== JSON.stringify(body.evidenceKeys)) throw new Error("IDEMPOTENCY_CONFLICT");
      return prior;
    }
    if (list.some((c) => c.status === "SUBMITTED" || c.status === "REVIEWING")) throw new Error("CLAIM_ALREADY_SUBMITTED");
    const c: MallOfflineClaim = { claimNo: body.claimNo, status: "SUBMITTED", declaredAmount: body.declaredAmount,
      declaredPaidAt: body.declaredPaidAt, submittedAt: now(), bankReference: body.bankReference, evidenceKeys: body.evidenceKeys };
    claims.set(key, [...list, c]);
    return c;
  },
  async mMallClaims(storeNo, orderNo) {
    return claims.get(k(storeNo || currentStoreNo(), orderNo)) ?? [];
  },
};
