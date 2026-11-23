// 开放 API 支付全链路的 mock（契约 openapi-pay-open.yaml v1.2；near-pay WebhookEnvelopes / OpenAppService 同口径）：
//   接入方建单 → 选方式发起支付 → 第三方渠道（mock）收银台付款 → 渠道带签名回调平台 → 平台验签、核金额、置成功
//   → 平台按 P2 签名、P4 加密推送 PAYMENT_SUCCEEDED 到接入方 notifyUrl（非 2xx 留待重推，重推同 eventId、新 nonce）
//   → 接入方验签、解密、按 eventId 幂等，再查单确认。
// 只在浏览器 / 测试里跑，不发真实网络请求：推送经 deliver() 交给调用方模拟的「接入方服务器」。
import type { OpenEndpoint } from "./open-api-catalog";
import { hmacBase64, hmacKeyOf, randomNonce, sha256Hex } from "./open-api-signer";

export interface PayApp { appId: string; appSecret?: string; encryptKey?: string; encryptSerial?: string; notifyUrl?: string | null }
export interface BizResult { status: number; code: number; message: string; data: unknown }
export interface Delivery { url: string; headers: Record<string, string>; body: string }
/** 接入方服务器（mock）：返回 HTTP 状态；2xx 算送达 */
export type Deliver = (d: Delivery) => Promise<number>;

type OrderStatus = "PENDING" | "PAYING" | "SUCCEEDED" | "CLOSED";
export interface SimOrder {
  appId: string; payOrderNo: string; outOrderNo: string; storeRef: string; subject: string; amountMinor: number; paidMinor: number; refundedMinor: number;
  currency: string; status: OrderStatus; payMethod: string | null; payChannel: string | null; outTradeNo: string | null;
  expireAt: number; createdAt: number; succeededAt: number | null; closedAt: number | null; stateVersion: number; requestHash: string;
}
export interface SimNotify { eventId: string; eventType: string; appId: string; payOrderNo: string; attempts: number; delivered: boolean; lastStatus: number | null }

/** 支付方式 → 渠道（mock 只认这几种；真实路由由平台按门店开通情况冻结） */
const CHANNEL_OF: Record<string, string> = { LINEPAY: "LINEPAY", CARD: "NEWEBPAY", JKOPAY: "JKOPAY" };
/** 渠道回调验签用的密钥：真实环境是平台与渠道之间的商户密钥，接入方看不到 */
const CHANNEL_SECRET = "mock-channel-hash-key";

const te = new TextEncoder();
const b64 = (u: Uint8Array) => btoa(String.fromCharCode(...u));
const err = (status: number, code: number, message: string): BizResult => ({ status, code, message, data: null });
const view = (o: SimOrder) => ({ payOrderNo: o.payOrderNo, outOrderNo: o.outOrderNo, status: o.status, amountMinor: o.amountMinor, paidMinor: o.paidMinor, refundedMinor: o.refundedMinor,
  currency: o.currency, payChannel: o.payChannel, payMethod: o.payMethod, expireAt: o.expireAt, succeededAt: o.succeededAt, closedAt: o.closedAt });

/** 第三方渠道（mock 收银台）：付款人在渠道页确认或取消，渠道再带签名回调平台 */
export interface MockChannel {
  /** 付款人在收银台点「确认付款」；amountMinor 可以故意改，模拟渠道回调金额不符 */
  pay(payUrl: string, opts?: { amountMinor?: number }): Promise<{ accepted: boolean; reason?: string }>;
  /** 伪造一条渠道回调（签名用错的密钥），平台必须拒收 */
  forge(payUrl: string): Promise<{ accepted: boolean; reason?: string }>;
}

export function createPaymentSim(opts: { now: () => number; appOf: (appId: string) => PayApp | undefined; deliver?: Deliver }) {
  const { now, appOf } = opts;
  const deliver: Deliver = opts.deliver ?? (async () => 200);
  const orders = new Map<string, SimOrder>(); const byOut = new Map<string, string>();
  const tokens = new Map<string, string>(); // 收银台令牌 → payOrderNo
  const notifies: SimNotify[] = [];
  let seq = 0;
  const no = (prefix: string) => `${prefix}${String(now()).slice(-10)}${String(++seq).padStart(4, "0")}`;

  /** P4 + P2：资源用 EncryptKey 做 AES-256-GCM（AAD = eventType|eventId），整个体按 HMAC 签名 */
  async function envelope(app: PayApp, n: SimNotify, data: unknown): Promise<Delivery> {
    const body: Record<string, unknown> = { eventId: n.eventId, eventType: n.eventType, stateVersion: orders.get(n.payOrderNo)!.stateVersion, createdAt: now() };
    const associated = `${n.eventType}|${n.eventId}`;
    if (app.encryptKey) {
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const key = await crypto.subtle.importKey("raw", te.encode(app.encryptKey), { name: "AES-GCM" }, false, ["encrypt"]);
      const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: te.encode(associated), tagLength: 128 }, key, te.encode(JSON.stringify(data))));
      body.resource = { algorithm: "AEAD_AES_256_GCM", ...(app.encryptSerial ? { keySerial: app.encryptSerial } : {}), nonce: b64(iv), associatedData: associated, ciphertext: b64(ct) };
    } else {
      body.data = data; // 过渡：接入方还没有 EncryptKey 时带明文（只含掩码内容）
    }
    const text = JSON.stringify(body);
    const ts = String(now()), nonce = randomNonce();
    const signature = await hmacBase64(hmacKeyOf(app.appSecret!), `${ts}\n${nonce}\n${await sha256Hex(text)}`);
    return { url: app.notifyUrl!, body: text, headers: { "content-type": "application/json; charset=UTF-8", "x-app-id": app.appId, "x-timestamp": ts, "x-nonce": nonce, "x-signature": signature, "x-event-id": n.eventId } };
  }

  async function push(n: SimNotify): Promise<void> {
    const app = appOf(n.appId); const o = orders.get(n.payOrderNo)!;
    if (!app?.notifyUrl || !app.appSecret) return; // 没配回调：不推送，接入方靠查单
    n.attempts += 1;
    const status = await deliver(await envelope(app, n, view(o)));
    n.lastStatus = status; n.delivered = status >= 200 && status < 300;
  }

  async function emit(o: SimOrder, eventType: string) {
    const n: SimNotify = { eventId: `EVT${no("")}`, eventType, appId: o.appId, payOrderNo: o.payOrderNo, attempts: 0, delivered: false, lastStatus: null };
    notifies.push(n); await push(n);
  }

  /** 平台收渠道回调：验渠道签名 → 核金额 → 置成功（幂等）→ 推送接入方 */
  async function channelNotify(payload: { token: string; outTradeNo: string; amountMinor: number; status: "PAID" }, signature: string): Promise<{ accepted: boolean; reason?: string }> {
    const text = JSON.stringify(payload);
    if (signature !== await hmacBase64(CHANNEL_SECRET, text)) return { accepted: false, reason: "渠道签名不符" };
    const o = orders.get(tokens.get(payload.token) ?? "");
    if (!o || o.outTradeNo !== payload.outTradeNo) return { accepted: false, reason: "渠道单号不存在" };
    if (o.status === "SUCCEEDED") return { accepted: true }; // 渠道重复回调：幂等，不再推送
    if (o.status !== "PAYING") return { accepted: false, reason: `订单状态 ${o.status}` };
    if (payload.amountMinor !== o.amountMinor) return { accepted: false, reason: "金额不符" };
    Object.assign(o, { status: "SUCCEEDED", paidMinor: payload.amountMinor, succeededAt: now(), stateVersion: o.stateVersion + 1 });
    await emit(o, "PAYMENT_SUCCEEDED");
    return { accepted: true };
  }

  const channel: MockChannel = {
    async pay(payUrl, o = {}) {
      const token = payUrl.split("/").pop()!; const order = orders.get(tokens.get(token) ?? "");
      if (!order) return { accepted: false, reason: "收银台令牌无效" };
      const payload = { token, outTradeNo: order.outTradeNo!, amountMinor: o.amountMinor ?? order.amountMinor, status: "PAID" as const };
      return channelNotify(payload, await hmacBase64(CHANNEL_SECRET, JSON.stringify(payload)));
    },
    async forge(payUrl) {
      const token = payUrl.split("/").pop()!; const order = orders.get(tokens.get(token) ?? "");
      const payload = { token, outTradeNo: order?.outTradeNo ?? "", amountMinor: order?.amountMinor ?? 0, status: "PAID" as const };
      return channelNotify(payload, await hmacBase64("attacker-key", JSON.stringify(payload)));
    },
  };

  /** 网关验签 / scope / P3 都过了之后的业务处理；返回 null 表示这个端点不归支付链路管（用样例回） */
  async function handle(app: { appId: string }, e: OpenEndpoint, params: Record<string, string>, query: URLSearchParams, rawBody: string): Promise<BizResult | null> {
    const body = rawBody ? (JSON.parse(rawBody) as Record<string, unknown>) : {};
    const mine = (payOrderNo: string) => { const o = orders.get(payOrderNo); return o && o.appId === app.appId ? o : null; };
    switch (e.key) {
      case "createOrder": {
        const { outOrderNo, storeRef, amountMinor, currency, subject } = body as Record<string, string & number>;
        if (!outOrderNo || !storeRef || !currency || !subject || !Number.isInteger(amountMinor) || amountMinor < 1) return err(400, 10400, "err.bad_request");
        const hash = await sha256Hex(JSON.stringify([storeRef, amountMinor, currency, subject]));
        const existing = orders.get(byOut.get(`${app.appId}|${outOrderNo}`) ?? "");
        if (existing) return existing.requestHash === hash ? { status: 200, code: 0, message: "OK", data: view(existing) } : err(409, 10409, "err.conflict");
        const expireMinutes = Number(body.expireMinutes ?? 30);
        const o: SimOrder = { appId: app.appId, payOrderNo: no("P"), outOrderNo, storeRef, subject, amountMinor, paidMinor: 0, refundedMinor: 0, currency, status: "PENDING", payMethod: null, payChannel: null, outTradeNo: null,
          expireAt: now() + expireMinutes * 60_000, createdAt: now(), succeededAt: null, closedAt: null, stateVersion: 1, requestHash: hash };
        orders.set(o.payOrderNo, o); byOut.set(`${app.appId}|${outOrderNo}`, o.payOrderNo);
        return { status: 200, code: 0, message: "OK", data: view(o) };
      }
      case "queryOrder": { const o = mine(params.payOrderNo); return o ? { status: 200, code: 0, message: "OK", data: view(o) } : err(404, 10404, "err.not_found"); }
      case "queryByOut": { const o = mine(byOut.get(`${app.appId}|${query.get("outOrderNo")}`) ?? ""); return o ? { status: 200, code: 0, message: "OK", data: view(o) } : err(404, 10404, "err.not_found"); }
      case "startPayment": {
        const o = mine(params.payOrderNo); if (!o) return err(404, 10404, "err.not_found");
        const payChannel = CHANNEL_OF[String(body.payMethod ?? "")]; if (!payChannel) return err(400, 10400, "err.bad_request");
        if (o.status !== "PENDING" && o.status !== "PAYING") return err(409, 10409, "err.conflict");
        if (now() > o.expireAt) return err(409, 10409, "err.conflict");
        const token = no("T"); tokens.set(token, o.payOrderNo);
        Object.assign(o, { status: "PAYING", payMethod: String(body.payMethod), payChannel, outTradeNo: no("CT"), stateVersion: o.stateVersion + 1 });
        return { status: 200, code: 0, message: "OK", data: { payOrderNo: o.payOrderNo, outTradeNo: o.outTradeNo, payChannel, payParams: { action: "REDIRECT", payUrl: `https://channel.mock/${payChannel.toLowerCase()}/checkout/${token}` } } };
      }
      case "closeOrder": {
        const o = mine(params.payOrderNo); if (!o) return err(404, 10404, "err.not_found");
        if (o.status === "SUCCEEDED") return err(409, 10409, "err.conflict"); // 已付款不能关，只能退款
        if (o.status !== "CLOSED") { Object.assign(o, { status: "CLOSED", closedAt: now(), stateVersion: o.stateVersion + 1 }); await emit(o, "PAYMENT_CLOSED"); }
        return { status: 200, code: 0, message: "OK", data: view(o) };
      }
      default: return null;
    }
  }

  return {
    handle, channel, notifies,
    order: (payOrderNo: string) => orders.get(payOrderNo),
    /** 重推所有没送达的推送（真实环境按退避 ≥ 24h）：同 eventId、新 nonce 与签名 */
    async redeliver() { for (const n of notifies.filter((x) => !x.delivered)) await push(n); },
  };
}
export type PaymentSim = ReturnType<typeof createPaymentSim>;

/** 接入方收推送：先验 P2 签名，再按 P4 解密 resource（没有 resource 时取明文 data） */
export async function openWebhook(appSecret: string, encryptKey: string | undefined, headers: Record<string, string | undefined>, body: string): Promise<{ valid: boolean; eventId?: string; eventType?: string; stateVersion?: number; data?: Record<string, unknown> }> {
  const ts = headers["x-timestamp"], nonce = headers["x-nonce"], signature = headers["x-signature"];
  if (!ts || !nonce || !signature) return { valid: false };
  if (signature !== await hmacBase64(hmacKeyOf(appSecret), `${ts}\n${nonce}\n${await sha256Hex(body)}`)) return { valid: false };
  const evt = JSON.parse(body) as { eventId: string; eventType: string; stateVersion?: number; resource?: { nonce: string; associatedData: string; ciphertext: string }; data?: Record<string, unknown> };
  if (headers["x-event-id"] !== evt.eventId) return { valid: false };
  let data = evt.data;
  if (evt.resource) {
    if (!encryptKey) return { valid: false };
    const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
    const key = await crypto.subtle.importKey("raw", te.encode(encryptKey), { name: "AES-GCM" }, false, ["decrypt"]);
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64(evt.resource.nonce), additionalData: te.encode(evt.resource.associatedData), tagLength: 128 }, key, unb64(evt.resource.ciphertext));
    data = JSON.parse(new TextDecoder().decode(plain));
  }
  return { valid: true, eventId: evt.eventId, eventType: evt.eventType, stateVersion: evt.stateVersion, data };
}
