// 开放 API 支付全链路的 mock（契约 openapi-pay-open.yaml v1.2；near-pay WebhookEnvelopes / OpenAppService 同口径）：
//   收款：接入方建单 → 选方式发起支付 → 第三方渠道（mock）收银台付款 → 渠道带签名回调平台 → 平台验签、核金额、置成功
//         → 平台推送 PAYMENT_SUCCEEDED（未付关单推 PAYMENT_CLOSED）
//   退款：接入方申请退款（日限额、可退余额、线下退款账号按 P3 加密）→ 渠道（mock）处理并带签名回调 → 平台置成功 / 失败
//         → 推送 REFUND_SUCCEEDED / REFUND_FAILED
//   推送：资源按 P4 加密（没有 EncryptKey 时带明文 data），HMAC 应用按 P2 签、RSA 应用用平台签名私钥签并带 X-Key-Serial；
//         非 2xx 留待重推（同 eventId、新 nonce）。接入方验签、解密、按 eventId 幂等，再查单 / 查退款为准。
//   授权：应用配了门店授权（grants）就按门店校验：建单 / 发起支付 / 关单要 COLLECT，查询要 READ，退款要 REFUND。
// 只在浏览器 / 测试里跑，不发真实网络请求：推送经 deliver() 交给调用方模拟的「接入方服务器」。
import type { OpenEndpoint } from "./open-api-catalog";
import { hmacBase64, hmacKeyOf, randomNonce, sha256Hex, verifyResponseRsa } from "./open-api-signer";

export interface PayApp {
  appId: string; appSecret?: string; encryptKey?: string; encryptSerial?: string; notifyUrl?: string | null; rsa?: { serial: string; publicPem: string };
  /** 门店授权：storeRef → READ / COLLECT / REFUND；不给 = 不校验门店（沙箱测试向量应用） */
  grants?: Record<string, string[]>;
  /** 日退款上限（最小单位）；不给 = 不限；0 = 不能退款（泄露应急锁定后） */
  dailyRefundLimitMinor?: number;
}
export interface BizResult { status: number; code: number; message: string; data: unknown }
export interface Delivery { url: string; headers: Record<string, string>; body: string }
/** 接入方服务器（mock）：返回 HTTP 状态；2xx 算送达 */
export type Deliver = (d: Delivery) => Promise<number>;
/** 推送签名（P2）：HMAC 应用用 AppSecret；RSA 应用由网关用平台签名私钥签，返回序列号 */
export type OutboundSigner = (app: PayApp, toSign: string) => Promise<{ signature: string; keySerial?: string }>;

type OrderStatus = "PENDING" | "PAYING" | "SUCCEEDED" | "CLOSED";
export interface SimOrder {
  appId: string; payOrderNo: string; outOrderNo: string; storeRef: string; subject: string; amountMinor: number; paidMinor: number; refundedMinor: number;
  currency: string; status: OrderStatus; payMethod: string | null; payChannel: string | null; outTradeNo: string | null;
  expireAt: number; createdAt: number; succeededAt: number | null; closedAt: number | null; stateVersion: number; requestHash: string;
}
export interface SimRefund {
  appId: string; payRefundNo: string; outRefundNo: string; payOrderNo: string; status: "PROCESSING" | "SUCCEEDED" | "FAILED"; amountMinor: number; currency: string;
  reason: string | null; refundAccount: Record<string, string> | null; createdAt: number; succeededAt: number | null; failReason: string | null; requestHash: string;
}
export interface SimNotify { eventId: string; eventType: string; appId: string; payOrderNo: string; data: unknown; attempts: number; delivered: boolean; lastStatus: number | null }
export type ChannelAck = { accepted: boolean; reason?: string };

/** 支付方式 → 渠道（mock 只认这几种；真实路由由平台按门店开通情况冻结） */
const CHANNEL_OF: Record<string, string> = { LINEPAY: "LINEPAY", CARD: "NEWEBPAY", JKOPAY: "JKOPAY" };
/** 渠道回调验签用的密钥：真实环境是平台与渠道之间的商户密钥，接入方看不到 */
const CHANNEL_SECRET = "mock-channel-hash-key";
const DAY = 24 * 60 * 60_000;

const te = new TextEncoder();
const b64 = (u: Uint8Array) => btoa(String.fromCharCode(...u));
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const ok = (data: unknown): BizResult => ({ status: 200, code: 0, message: "OK", data });
const err = (status: number, code: number, message: string): BizResult => ({ status, code, message, data: null });
const NOT_FOUND = err(404, 10404, "err.not_found");
const STORE_FORBIDDEN = err(403, 10403, "err.forbidden");
const orderView = (o: SimOrder) => ({ payOrderNo: o.payOrderNo, outOrderNo: o.outOrderNo, status: o.status, amountMinor: o.amountMinor, paidMinor: o.paidMinor, refundedMinor: o.refundedMinor,
  currency: o.currency, payChannel: o.payChannel, payMethod: o.payMethod, expireAt: o.expireAt, succeededAt: o.succeededAt, closedAt: o.closedAt });
const refundView = (r: SimRefund) => ({ payRefundNo: r.payRefundNo, outRefundNo: r.outRefundNo, payOrderNo: r.payOrderNo, status: r.status, amountMinor: r.amountMinor, currency: r.currency,
  succeededAt: r.succeededAt, ...(r.refundAccount ? { refundAccount: r.refundAccount } : {}) });
/** 查询只回掩码：收款账号是 P3 密文，平台解密后只存掩码，mock 直接回 **** */
const mask = (_v: unknown) => "****";

/** 第三方渠道（mock）：收银台付款、退款处理，处理完带签名回调平台 */
export interface MockChannel {
  /** 付款人在收银台点「确认付款」；amountMinor 可以故意改，模拟渠道回调金额不符 */
  pay(payUrl: string, opts?: { amountMinor?: number }): Promise<ChannelAck>;
  /** 伪造一条付款回调（签名用错的密钥），平台必须拒收 */
  forge(payUrl: string): Promise<ChannelAck>;
  /** 渠道处理退款并回调平台：成功或失败（如原卡已注销） */
  settleRefund(payRefundNo: string, opts?: { fail?: string }): Promise<ChannelAck>;
  /** 伪造一条退款成功回调 */
  forgeRefund(payRefundNo: string): Promise<ChannelAck>;
  /** 渠道侧待处理的退款单号 */
  pendingRefunds(): string[];
}

export function createPaymentSim(opts: { now: () => number; appOf: (appId: string) => PayApp | undefined; deliver?: Deliver; sign?: OutboundSigner }) {
  const { now, appOf } = opts;
  const deliver: Deliver = opts.deliver ?? (async () => 200);
  const sign: OutboundSigner = opts.sign ?? (async (app, toSign) => ({ signature: await hmacBase64(hmacKeyOf(app.appSecret!), toSign) }));
  const orders = new Map<string, SimOrder>(); const byOut = new Map<string, string>();
  const refunds = new Map<string, SimRefund>(); const refundByOut = new Map<string, string>();
  const tokens = new Map<string, string>(); // 收银台令牌 → payOrderNo
  const channelRefunds = new Set<string>(); // 渠道侧待处理退款
  const notifies: SimNotify[] = [];
  let seq = 0;
  const no = (prefix: string) => `${prefix}${String(now()).slice(-10)}${String(++seq).padStart(4, "0")}`;

  /** P4 + P2：资源用 EncryptKey 做 AES-256-GCM（AAD = eventType|eventId），整个体按应用的密钥方案签名 */
  async function envelope(app: PayApp, n: SimNotify): Promise<Delivery> {
    const body: Record<string, unknown> = { eventId: n.eventId, eventType: n.eventType, stateVersion: orders.get(n.payOrderNo)!.stateVersion, createdAt: now() };
    const associated = `${n.eventType}|${n.eventId}`;
    if (app.encryptKey) {
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const key = await crypto.subtle.importKey("raw", te.encode(app.encryptKey), { name: "AES-GCM" }, false, ["encrypt"]);
      const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: te.encode(associated), tagLength: 128 }, key, te.encode(JSON.stringify(n.data))));
      body.resource = { algorithm: "AEAD_AES_256_GCM", ...(app.encryptSerial ? { keySerial: app.encryptSerial } : {}), nonce: b64(iv), associatedData: associated, ciphertext: b64(ct) };
    } else {
      body.data = n.data; // 过渡：接入方还没有 EncryptKey 时带明文（只含掩码内容）
    }
    const text = JSON.stringify(body);
    const ts = String(now()), nonce = randomNonce();
    const s = await sign(app, `${ts}\n${nonce}\n${await sha256Hex(text)}`);
    return { url: app.notifyUrl!, body: text, headers: { "content-type": "application/json; charset=UTF-8", "x-app-id": app.appId, "x-timestamp": ts, "x-nonce": nonce,
      ...(s.keySerial ? { "x-key-serial": s.keySerial } : {}), "x-signature": s.signature, "x-event-id": n.eventId } };
  }

  async function push(n: SimNotify): Promise<void> {
    const app = appOf(n.appId);
    if (!app?.notifyUrl || (!app.appSecret && !app.rsa)) return; // 没配回调：不推送，接入方靠查单
    n.attempts += 1;
    const status = await deliver(await envelope(app, n));
    n.lastStatus = status; n.delivered = status >= 200 && status < 300;
  }

  async function emit(o: SimOrder, eventType: string, data: unknown) {
    const n: SimNotify = { eventId: `EVT${no("")}`, eventType, appId: o.appId, payOrderNo: o.payOrderNo, data, attempts: 0, delivered: false, lastStatus: null };
    notifies.push(n); await push(n);
  }

  const channelSign = (payload: unknown) => hmacBase64(CHANNEL_SECRET, JSON.stringify(payload));

  /** 平台收渠道付款回调：验渠道签名 → 核金额 → 置成功（幂等）→ 推送接入方 */
  async function paymentNotify(payload: { token: string; outTradeNo: string; amountMinor: number; status: "PAID" }, signature: string): Promise<ChannelAck> {
    if (signature !== await channelSign(payload)) return { accepted: false, reason: "渠道签名不符" };
    const o = orders.get(tokens.get(payload.token) ?? "");
    if (!o || o.outTradeNo !== payload.outTradeNo) return { accepted: false, reason: "渠道单号不存在" };
    if (o.status === "SUCCEEDED") return { accepted: true }; // 渠道重复回调：幂等，不再推送
    if (o.status !== "PAYING") return { accepted: false, reason: `订单状态 ${o.status}` };
    if (payload.amountMinor !== o.amountMinor) return { accepted: false, reason: "金额不符" };
    Object.assign(o, { status: "SUCCEEDED", paidMinor: payload.amountMinor, succeededAt: now(), stateVersion: o.stateVersion + 1 });
    await emit(o, "PAYMENT_SUCCEEDED", orderView(o));
    return { accepted: true };
  }

  /** 平台收渠道退款回调：验签 → 核金额 → 成功累计已退金额 / 失败释放额度（幂等）→ 推送接入方 */
  async function refundNotify(payload: { payRefundNo: string; amountMinor: number; status: "SUCCEEDED" | "FAILED"; failReason?: string }, signature: string): Promise<ChannelAck> {
    if (signature !== await channelSign(payload)) return { accepted: false, reason: "渠道签名不符" };
    const r = refunds.get(payload.payRefundNo);
    if (!r) return { accepted: false, reason: "退款单不存在" };
    if (r.status !== "PROCESSING") return { accepted: true }; // 重复回调：幂等
    if (payload.amountMinor !== r.amountMinor) return { accepted: false, reason: "金额不符" };
    const o = orders.get(r.payOrderNo)!;
    channelRefunds.delete(r.payRefundNo);
    if (payload.status === "SUCCEEDED") {
      Object.assign(r, { status: "SUCCEEDED", succeededAt: now() });
      Object.assign(o, { refundedMinor: o.refundedMinor + r.amountMinor, stateVersion: o.stateVersion + 1 });
      await emit(o, "REFUND_SUCCEEDED", refundView(r));
    } else {
      Object.assign(r, { status: "FAILED", failReason: payload.failReason ?? "渠道退款失败" });
      o.stateVersion += 1;
      await emit(o, "REFUND_FAILED", { ...refundView(r), failReason: r.failReason });
    }
    return { accepted: true };
  }

  const tokenOf = (payUrl: string) => payUrl.split("/").pop()!;
  const channel: MockChannel = {
    async pay(payUrl, o = {}) {
      const token = tokenOf(payUrl); const order = orders.get(tokens.get(token) ?? "");
      if (!order) return { accepted: false, reason: "收银台令牌无效" };
      const payload = { token, outTradeNo: order.outTradeNo!, amountMinor: o.amountMinor ?? order.amountMinor, status: "PAID" as const };
      return paymentNotify(payload, await channelSign(payload));
    },
    async forge(payUrl) {
      const token = tokenOf(payUrl); const order = orders.get(tokens.get(token) ?? "");
      const payload = { token, outTradeNo: order?.outTradeNo ?? "", amountMinor: order?.amountMinor ?? 0, status: "PAID" as const };
      return paymentNotify(payload, await hmacBase64("attacker-key", JSON.stringify(payload)));
    },
    async settleRefund(payRefundNo, o = {}) {
      const r = refunds.get(payRefundNo); if (!r) return { accepted: false, reason: "退款单不存在" };
      const payload = { payRefundNo, amountMinor: r.amountMinor, status: o.fail ? ("FAILED" as const) : ("SUCCEEDED" as const), ...(o.fail ? { failReason: o.fail } : {}) };
      return refundNotify(payload, await channelSign(payload));
    },
    async forgeRefund(payRefundNo) {
      const r = refunds.get(payRefundNo);
      const payload = { payRefundNo, amountMinor: r?.amountMinor ?? 0, status: "SUCCEEDED" as const };
      return refundNotify(payload, await hmacBase64("attacker-key", JSON.stringify(payload)));
    },
    pendingRefunds: () => [...channelRefunds],
  };

  /** 门店授权：应用没配 grants 就不校验 */
  const granted = (app: PayApp, storeRef: string, action: "READ" | "COLLECT" | "REFUND") => !app.grants || (app.grants[storeRef] ?? []).includes(action);
  /** 当日（滚动 24h）已占用的退款额度：处理中 + 成功 */
  const refundedToday = (appId: string) => [...refunds.values()].filter((r) => r.appId === appId && r.status !== "FAILED" && now() - r.createdAt < DAY).reduce((s, r) => s + r.amountMinor, 0);

  /** 网关验签 / scope / P3 都过了之后的业务处理；返回 null 表示这个端点不归支付链路管（用样例回） */
  async function handle(gwApp: { appId: string }, e: OpenEndpoint, params: Record<string, string>, query: URLSearchParams, rawBody: string): Promise<BizResult | null> {
    const app: PayApp = appOf(gwApp.appId) ?? gwApp;
    const body = rawBody ? (JSON.parse(rawBody) as Record<string, unknown>) : {};
    const mine = (payOrderNo: string) => { const o = orders.get(payOrderNo); return o && o.appId === app.appId ? o : null; };
    switch (e.key) {
      case "createOrder": {
        const { outOrderNo, storeRef, amountMinor, currency, subject } = body as Record<string, string & number>;
        if (!outOrderNo || !storeRef || !currency || !subject || !Number.isInteger(amountMinor) || amountMinor < 1) return err(400, 10400, "err.bad_request");
        if (!granted(app, storeRef, "COLLECT")) return STORE_FORBIDDEN;
        const hash = await sha256Hex(JSON.stringify([storeRef, amountMinor, currency, subject]));
        const existing = orders.get(byOut.get(`${app.appId}|${outOrderNo}`) ?? "");
        if (existing) return existing.requestHash === hash ? ok(orderView(existing)) : err(409, 10409, "err.conflict");
        const expireMinutes = Number(body.expireMinutes ?? 30);
        const o: SimOrder = { appId: app.appId, payOrderNo: no("P"), outOrderNo, storeRef, subject, amountMinor, paidMinor: 0, refundedMinor: 0, currency, status: "PENDING", payMethod: null, payChannel: null, outTradeNo: null,
          expireAt: now() + expireMinutes * 60_000, createdAt: now(), succeededAt: null, closedAt: null, stateVersion: 1, requestHash: hash };
        orders.set(o.payOrderNo, o); byOut.set(`${app.appId}|${outOrderNo}`, o.payOrderNo);
        return ok(orderView(o));
      }
      case "queryOrder": case "queryByOut": {
        const o = mine(e.key === "queryOrder" ? params.payOrderNo : byOut.get(`${app.appId}|${query.get("outOrderNo")}`) ?? "");
        if (!o) return NOT_FOUND;
        return granted(app, o.storeRef, "READ") ? ok(orderView(o)) : STORE_FORBIDDEN;
      }
      case "startPayment": {
        const o = mine(params.payOrderNo); if (!o) return NOT_FOUND;
        if (!granted(app, o.storeRef, "COLLECT")) return STORE_FORBIDDEN;
        const payChannel = CHANNEL_OF[String(body.payMethod ?? "")]; if (!payChannel) return err(400, 10400, "err.bad_request");
        if ((o.status !== "PENDING" && o.status !== "PAYING") || now() > o.expireAt) return err(409, 10409, "err.conflict");
        const token = no("T"); tokens.set(token, o.payOrderNo);
        Object.assign(o, { status: "PAYING", payMethod: String(body.payMethod), payChannel, outTradeNo: no("CT"), stateVersion: o.stateVersion + 1 });
        return ok({ payOrderNo: o.payOrderNo, outTradeNo: o.outTradeNo, payChannel, payParams: { action: "REDIRECT", payUrl: `https://channel.mock/${payChannel.toLowerCase()}/checkout/${token}` } });
      }
      case "closeOrder": {
        const o = mine(params.payOrderNo); if (!o) return NOT_FOUND;
        if (!granted(app, o.storeRef, "COLLECT")) return STORE_FORBIDDEN;
        if (o.status === "SUCCEEDED") return err(409, 10409, "err.conflict"); // 已付款不能关，只能退款
        if (o.status !== "CLOSED") { Object.assign(o, { status: "CLOSED", closedAt: now(), stateVersion: o.stateVersion + 1 }); await emit(o, "PAYMENT_CLOSED", orderView(o)); }
        return ok(orderView(o));
      }
      case "refund": {
        const o = mine(params.payOrderNo); if (!o) return NOT_FOUND;
        if (!granted(app, o.storeRef, "REFUND")) return STORE_FORBIDDEN;
        const { outRefundNo, amountMinor } = body as Record<string, string & number>;
        if (!outRefundNo || !Number.isInteger(amountMinor) || amountMinor < 1) return err(400, 10400, "err.bad_request");
        const account = body.refundAccount as Record<string, string> | undefined;
        const hash = await sha256Hex(JSON.stringify([o.payOrderNo, amountMinor]));
        const existing = refunds.get(refundByOut.get(`${app.appId}|${outRefundNo}`) ?? "");
        if (existing) return existing.requestHash === hash ? ok(refundView(existing)) : err(409, 10409, "err.conflict");
        if (o.status !== "SUCCEEDED") return err(409, 10409, "err.conflict");
        const inFlight = [...refunds.values()].filter((r) => r.payOrderNo === o.payOrderNo && r.status === "PROCESSING").reduce((s, r) => s + r.amountMinor, 0);
        if (amountMinor > o.paidMinor - o.refundedMinor - inFlight) return err(409, 10409, "err.pay.open.refund_exceeds_paid");
        if (app.dailyRefundLimitMinor != null && refundedToday(app.appId) + amountMinor > app.dailyRefundLimitMinor) return err(409, 10409, "err.pay.open.refund_limit_exceeded");
        const r: SimRefund = { appId: app.appId, payRefundNo: no("R"), outRefundNo, payOrderNo: o.payOrderNo, status: "PROCESSING", amountMinor, currency: o.currency, reason: (body.reason as string) ?? null,
          refundAccount: account ? Object.fromEntries(Object.keys(account).map((k) => [k, mask(account[k])])) : null, createdAt: now(), succeededAt: null, failReason: null, requestHash: hash };
        refunds.set(r.payRefundNo, r); refundByOut.set(`${app.appId}|${outRefundNo}`, r.payRefundNo); channelRefunds.add(r.payRefundNo);
        return ok(refundView(r));
      }
      case "queryRefund": {
        const r = refunds.get(params.payRefundNo); if (!r || r.appId !== app.appId) return NOT_FOUND;
        return granted(app, orders.get(r.payOrderNo)!.storeRef, "READ") ? ok(refundView(r)) : STORE_FORBIDDEN;
      }
      default: return null;
    }
  }

  return {
    handle, channel, notifies,
    order: (payOrderNo: string) => orders.get(payOrderNo),
    refund: (payRefundNo: string) => refunds.get(payRefundNo),
    /** 重推所有没送达的推送（真实环境按退避 ≥ 24h）：同 eventId、新 nonce 与签名 */
    async redeliver() { for (const n of notifies.filter((x) => !x.delivered)) await push(n); },
  };
}
export type PaymentSim = ReturnType<typeof createPaymentSim>;

export type WebhookVerifier = { appSecret: string } | { platformKeys: { serial: string; usage: string; publicKeyPem: string }[] };
export interface OpenedWebhook { valid: boolean; eventId?: string; eventType?: string; stateVersion?: number; data?: Record<string, unknown> }

/** 接入方收推送：先验 P2 签名（HMAC 用 AppSecret；RSA 按 X-Key-Serial 用平台签名公钥），再按 P4 解密 resource（没有 resource 时取明文 data） */
export async function openWebhook(verifier: WebhookVerifier, encryptKey: string | undefined, headers: Record<string, string | undefined>, body: string): Promise<OpenedWebhook> {
  const ts = headers["x-timestamp"], nonce = headers["x-nonce"], signature = headers["x-signature"];
  if (!ts || !nonce || !signature) return { valid: false };
  if ("appSecret" in verifier) {
    if (signature !== await hmacBase64(hmacKeyOf(verifier.appSecret), `${ts}\n${nonce}\n${await sha256Hex(body)}`)) return { valid: false };
  } else if ((await verifyResponseRsa(verifier.platformKeys, headers, body)).status !== "VALID") {
    return { valid: false };
  }
  const evt = JSON.parse(body) as { eventId: string; eventType: string; stateVersion?: number; resource?: { nonce: string; associatedData: string; ciphertext: string }; data?: Record<string, unknown> };
  if (headers["x-event-id"] !== evt.eventId) return { valid: false };
  let data = evt.data;
  if (evt.resource) {
    if (!encryptKey) return { valid: false };
    const key = await crypto.subtle.importKey("raw", te.encode(encryptKey), { name: "AES-GCM" }, false, ["decrypt"]);
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64(evt.resource.nonce), additionalData: te.encode(evt.resource.associatedData), tagLength: 128 }, key, unb64(evt.resource.ciphertext));
    data = JSON.parse(new TextDecoder().decode(plain));
  }
  return { valid: true, eventId: evt.eventId, eventType: evt.eventType, stateVersion: evt.stateVersion, data };
}
