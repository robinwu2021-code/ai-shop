// 开放 API 接口清单（SY12）。对外契约真源是 near-pay docs/api/openapi-pay-open.yaml（v1.2）；
// 这里只做展示与调用模拟（SY13）的样例，不是第二份契约。照搬 near-pay ops-web/lib/open-api/catalog.ts，
// 路径改成相对对外基址（UME 生产 https://tpay.neargo.ai/openapi/v1；P1 待签串的 PATH 用实际请求路径 /openapi/v1/...）。

export type HttpMethod = "GET" | "POST";
export interface L { zh: string; en: string }
export interface ApiParam { name: string; in: "path" | "query"; required: boolean; example: string; description: L }
export interface ApiField { name: string; type: string; required: boolean; description: L; sensitive?: boolean }
export interface OpenEndpoint {
  key: string; method: HttpMethod;
  /** 相对对外基址的路径，如 /orders/{payOrderNo} */
  path: string;
  title: L; summary: L;
  /** 守卫要求的 scope；ping / certificates 只要求签名通过 */
  scope: "pay:create" | "pay:read" | "pay:refund" | null;
  /** 接入申请里的接口权限（READ / COLLECT / REFUND） */
  action: "COLLECT" | "READ" | "REFUND" | null;
  params: ApiParam[]; body: ApiField[]; sampleBody?: Record<string, unknown>; idempotency?: string;
  /** 只有沙箱应用能调（生产应用调用回 403） */
  sandboxOnly?: boolean;
  /** 契约 x-sensitive 字段（JSON 点路径）：必须按 P3 加密 */
  sensitive?: string[];
  /** mock 网关返回的 data 样例 */
  sampleResponse: unknown;
}

/** 对外基址：默认 UME 生产；别的环境用 NEXT_PUBLIC_OPEN_API_BASE 覆盖（必须是 https 绝对地址） */
export const DEFAULT_OPEN_API_BASE = "https://tpay.neargo.ai/openapi/v1";
export function resolveOpenApiBase(configured = process.env.NEXT_PUBLIC_OPEN_API_BASE): { origin: string; prefix: string; base: string } {
  const raw = (configured || DEFAULT_OPEN_API_BASE).replace(/\/+$/, "");
  let u: URL;
  try { u = new URL(raw); } catch { throw new Error(`NEXT_PUBLIC_OPEN_API_BASE 不是合法地址：${raw}`); }
  if (u.protocol !== "https:" || u.search || u.hash) throw new Error(`NEXT_PUBLIC_OPEN_API_BASE 必须是 https 地址且不带查询串，例如 ${DEFAULT_OPEN_API_BASE}`);
  return { origin: u.origin, prefix: u.pathname.replace(/\/+$/, ""), base: raw };
}

const p = (name: string, example: string, zh: string, en: string, where: "path" | "query" = "path", required = true): ApiParam => ({ name, in: where, required, example, description: { zh, en } });
const f = (name: string, type: string, required: boolean, zh: string, en: string, sensitive?: boolean): ApiField => ({ name, type, required, description: { zh, en }, ...(sensitive ? { sensitive } : {}) });
const order = { payOrderNo: "P20261026000001", outOrderNo: "M20261026001", status: "PENDING", amountMinor: 12000, currency: "TWD", subject: "订单 M20261026001", storeRef: "S-TW-001", expireAt: 1761467400000, createdAt: 1761465600000 };
const payOrderNo = p("payOrderNo", "P20261026000001", "平台支付单号", "Platform payment order no.");

export const OPEN_ENDPOINTS: OpenEndpoint[] = [
  { key: "ping", method: "POST", path: "/ping", title: { zh: "联调 Ping", en: "Ping" }, sandboxOnly: true, scope: null, action: null,
    summary: { zh: "沙箱专用：验证签名、时间窗、nonce、IP 白名单；回显应用、scope、密钥版本与请求体", en: "Sandbox only: checks signature, time window, nonce and IP allow-list; echoes app, scopes, key version and body" },
    params: [], body: [f("(any JSON)", "object", false, "原样回显到 data.echo", "Echoed back in data.echo")], sampleBody: { hello: "near-pay" },
    sampleResponse: { appNo: "APP7Q2K9XW", environment: "SANDBOX", scopes: ["pay:create", "pay:read"], keyVersion: 1, serverTime: 1761465600000, echo: { hello: "near-pay" } } },
  { key: "certificates", method: "GET", path: "/certificates", title: { zh: "平台公钥", en: "Platform certificates" }, scope: null, action: null,
    summary: { zh: "RSA 方案：平台签名公钥（验响应与推送）与加密公钥（加密敏感字段），按 X-Key-Serial 选用", en: "RSA scheme: platform signing key (verify responses/webhooks) and encryption key (sensitive fields), selected by X-Key-Serial" },
    params: [], body: [],
    sampleResponse: [{ serial: "P2026112200000001", usage: "SIGN", algorithm: "RSA_SHA256", status: "ACTIVE", fingerprint: "sha256:…", publicKeyPem: "-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----\n", validFrom: null, validTo: null, activatedAt: 1795300000000, overlapUntil: null }] },
  { key: "createOrder", method: "POST", path: "/orders", title: { zh: "建单", en: "Create order" }, scope: "pay:create", action: "COLLECT", idempotency: "outOrderNo", sensitive: ["payer.phone", "payer.email", "payer.name"],
    summary: { zh: "幂等：同 outOrderNo 同内容返回原单，内容不同 409", en: "Idempotent on outOrderNo: same content returns the original order, different content 409" },
    params: [], body: [
      f("outOrderNo", "string(64)", true, "接入方单号", "Your order no."),
      f("storeRef", "string", true, "门店号（授权须含 COLLECT）", "Store no. (grant must include COLLECT)"),
      f("amountMinor", "integer ≥ 1", true, "金额，最小货币单位", "Amount in minor units"),
      f("currency", "string", true, "须与门店市场币种一致", "Must match the store market currency"),
      f("subject", "string", true, "商品描述", "Description"),
      f("expireMinutes", "integer 1–1440", false, "默认 30", "Default 30"),
      f("bizNo", "string", false, "业务单号", "Business no."),
      f("payer.phone / email / name", "string", false, "付款人信息，按 P3 加密", "Payer info, P3-encrypted", true),
    ],
    sampleBody: { outOrderNo: "M20261026001", storeRef: "S-TW-001", amountMinor: 12000, currency: "TWD", subject: "订单 M20261026001" }, sampleResponse: order },
  { key: "queryByOut", method: "GET", path: "/orders", title: { zh: "按接入方单号查单", en: "Query by outOrderNo" }, scope: "pay:read", action: "READ",
    summary: { zh: "按 outOrderNo 查单", en: "Look up an order by outOrderNo" },
    params: [p("outOrderNo", "M20261026001", "接入方单号", "Your order no.", "query")], body: [], sampleResponse: order },
  { key: "queryOrder", method: "GET", path: "/orders/{payOrderNo}", title: { zh: "查单", en: "Query order" }, scope: "pay:read", action: "READ",
    summary: { zh: "敏感字段只回掩码", en: "Sensitive fields are masked" },
    params: [payOrderNo], body: [], sampleResponse: { ...order, masked: { phone: "0912****678" } } },
  { key: "startPayment", method: "POST", path: "/orders/{payOrderNo}/pay", title: { zh: "发起支付", en: "Start payment" }, scope: "pay:create", action: "COLLECT",
    summary: { zh: "选支付方式发起（冻结路由）", en: "Start with a pay method (route frozen)" },
    params: [payOrderNo],
    body: [f("payMethod", "string", true, "门店可用支付方式（见 pay-methods）", "A pay method available to the store"), f("payerOpenId", "string", false, "小程序/公众号付款人", "Mini-program payer"), f("payerAppId", "string", false, "付款人 AppId", "Payer AppId")],
    sampleBody: { payMethod: "LINEPAY" }, sampleResponse: { payOrderNo: order.payOrderNo, status: "PAYING", action: "REDIRECT", payUrl: "https://sandbox.example.com/pay/abc" } },
  { key: "closeOrder", method: "POST", path: "/orders/{payOrderNo}/close", title: { zh: "关单", en: "Close order" }, scope: "pay:create", action: "COLLECT",
    summary: { zh: "关单前必须先查单", en: "Query before closing" },
    params: [payOrderNo], body: [f("reason", "string", false, "关单原因", "Reason")], sampleBody: { reason: "用户取消" }, sampleResponse: { ...order, status: "CLOSED" } },
  { key: "refund", method: "POST", path: "/orders/{payOrderNo}/refunds", title: { zh: "申请退款", en: "Refund" }, scope: "pay:refund", action: "REFUND", idempotency: "outRefundNo",
    sensitive: ["refundAccount.bankCode", "refundAccount.accountNo", "refundAccount.accountName"],
    summary: { zh: "受应用日退款上限约束；线下退款带收款账号（P3 加密）", en: "Bound by the app daily refund limit; offline refunds carry a P3-encrypted account" },
    params: [payOrderNo],
    body: [f("outRefundNo", "string(64)", true, "接入方退款单号", "Your refund no."), f("amountMinor", "integer ≥ 1", true, "退款金额", "Refund amount"), f("reason", "string", false, "退款原因", "Reason"), f("refundAccount.*", "object", false, "线下退款收款账号，按 P3 加密", "Offline refund account, P3-encrypted", true)],
    sampleBody: { outRefundNo: "R20261026001", amountMinor: 2000, reason: "部分退款" }, sampleResponse: { payRefundNo: "R20261026000001", outRefundNo: "R20261026001", payOrderNo: order.payOrderNo, status: "PROCESSING", amountMinor: 2000, currency: "TWD" } },
  { key: "queryRefund", method: "GET", path: "/refunds/{payRefundNo}", title: { zh: "查退款", en: "Query refund" }, scope: "pay:read", action: "READ",
    summary: { zh: "收款账号只回掩码", en: "Account fields are masked" },
    params: [p("payRefundNo", "R20261026000001", "平台退款单号", "Platform refund no.")], body: [],
    sampleResponse: { payRefundNo: "R20261026000001", outRefundNo: "R20261026001", payOrderNo: order.payOrderNo, status: "SUCCEEDED", amountMinor: 2000, currency: "TWD" } },
  { key: "payMethods", method: "GET", path: "/stores/{storeRef}/pay-methods", title: { zh: "门店可用支付方式", en: "Store pay methods" }, scope: "pay:read", action: "READ",
    summary: { zh: "门店收款路由 × 已启用支付产品，同一方式取优先级最高的一条", en: "Store routes × enabled products; highest priority per method" },
    params: [p("storeRef", "S-TW-001", "门店号", "Store no."), p("market", "", "市场，默认门店市场", "Market, defaults to the store market", "query", false)], body: [],
    sampleResponse: [{ payMethod: "LINEPAY", payChannel: "LINEPAY", accessMode: "REDIRECT" }, { payMethod: "CARD", payChannel: "NEWEBPAY", accessMode: "REDIRECT" }] },
];

export const COMMON_HEADERS: { name: string; required: L; description: L }[] = [
  { name: "X-App-Id", required: { zh: "必填", en: "Required" }, description: { zh: "应用编号（AppId）", en: "App no. (AppId)" } },
  { name: "X-Timestamp", required: { zh: "必填", en: "Required" }, description: { zh: "毫秒时间戳，与服务器相差 ±300 秒内", en: "Milliseconds, within ±300 s of the server" } },
  { name: "X-Nonce", required: { zh: "必填", en: "Required" }, description: { zh: "8–64 位，窗口内不得重复", en: "8–64 chars, unique within the window" } },
  { name: "X-Signature", required: { zh: "必填", en: "Required" }, description: { zh: "HMAC：Base64(HMAC-SHA256(去前缀的 AppSecret, 待签串))；RSA：Base64(SHA256withRSA(商家私钥, 待签串))", en: "HMAC: Base64(HMAC-SHA256(AppSecret without prefix, string-to-sign)); RSA: Base64(SHA256withRSA(merchant private key, string-to-sign))" } },
  { name: "X-Key-Serial", required: { zh: "HMAC 推荐 / RSA 必填", en: "HMAC recommended / RSA required" }, description: { zh: "HMAC：AppSecret 版本；RSA：商家公钥序列号（K…）", en: "HMAC: AppSecret version; RSA: merchant public key serial (K…)" } },
  { name: "X-Encrypt-Serial", required: { zh: "条件", en: "Conditional" }, description: { zh: "有 P3 敏感字段时必填", en: "Required when P3 sensitive fields are present" } },
];

export const OPEN_ERROR_CODES: { http: number; code: number; name: string; when: L }[] = [
  { http: 401, code: 10401, name: "UNAUTHORIZED", when: { zh: "AppId 不存在/停用/环境不符/签名错/密钥已吊销/缺签名头（统一不细分）", en: "Unknown/suspended app, wrong environment, bad signature, revoked key or missing headers (not distinguished)" } },
  { http: 401, code: 70095, name: "PAY_OPEN_TIMESTAMP_EXPIRED", when: { zh: "X-Timestamp 不在 ±300 秒内", en: "X-Timestamp outside ±300 s" } },
  { http: 401, code: 70096, name: "PAY_OPEN_REPLAYED", when: { zh: "X-Nonce 在窗口内用过", en: "X-Nonce reused within the window" } },
  { http: 403, code: 10403, name: "FORBIDDEN", when: { zh: "来源 IP 不在白名单；缺该接口的 scope；生产应用调用 ping", en: "IP not allowed; missing scope; production app calling ping" } },
  { http: 400, code: 70097, name: "PAY_OPEN_FIELD_ENCRYPT_REQUIRED", when: { zh: "敏感字段传了明文", en: "Sensitive field sent in plain text" } },
  { http: 400, code: 70098, name: "PAY_OPEN_FIELD_DECRYPT_FAILED", when: { zh: "加密字段解不开", en: "Encrypted field cannot be decrypted" } },
  { http: 429, code: 10429, name: "TOO_MANY_REQUESTS", when: { zh: "超过应用 QPS", en: "App QPS exceeded" } },
  { http: 503, code: 70099, name: "PAY_KEY_SERVICE_UNAVAILABLE", when: { zh: "密钥服务不可用", en: "Key service unavailable" } },
];

/** 把 {param} 换成值，返回相对路径与查询参数（查询值原样；签名用的规范化查询串由 signer 另算） */
export function resolvePath(endpoint: OpenEndpoint, values: Record<string, string>): { path: string; query: [string, string][] } {
  let path = endpoint.path; const query: [string, string][] = [];
  for (const prm of endpoint.params) {
    const v = (values[prm.name] ?? "").trim();
    if (prm.in === "path") path = path.replace(`{${prm.name}}`, encodeURIComponent(v));
    else if (v) query.push([prm.name, v]);
  }
  return { path, query };
}
