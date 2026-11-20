// 支付端（near-pay）运营接口客户端：/pay-ops/v2（TDD-支付中心运营页面 §2.1）。
//
// - 基础路径读 NEXT_PUBLIC_PAY_OPS_API_BASE，默认同源 /oms-api/pay-ops/v2（near-pay 运营端今天在用的那条，
//   不新增对外暴露）；网关代理就绪后只改这个配置。只接受同源绝对路径，杜绝把令牌发去别的域。
// - 响应：可能是裸值，也可能是 {code, data} 信封；详情是 {record, version, allowedActions}。
// - 错误：v2 信封 {code, reasonCode, message, requestId} → PayOpsError。
// - 命令：带 requestNo（幂等号，由路径 + 请求体决定：超时重试原样再发是同一个号，改了内容再提交是新号 ——
//   near-pay 对「同号不同参」回 409 IDEMPOTENCY_CONFLICT）、expectedVersion、reason。
//   订单与支付异常的命令没有幂等号与版本（near-pay 侧如此），传 { idempotent: false }。
//
// 放在 lib/pay-ops 而不是 lib/api/https：后者受 shop openapi 对账闸门约束，而这些端点属于 near-pay。

export const DEFAULT_PAY_OPS_BASE = "/oms-api/pay-ops/v2";

export interface PayPage<T> { records: T[]; total: number }
export interface PayDetail<T> { record: T; version?: number | string; allowedActions?: string[] }

export class PayOpsError extends Error {
  status: number;
  reasonCode?: string;
  requestId?: string;
  constructor(message: string, init: { status: number; reasonCode?: string; requestId?: string }) {
    super(message);
    this.name = "PayOpsError";
    this.status = init.status;
    this.reasonCode = init.reasonCode;
    this.requestId = init.requestId;
  }
}

export function resolvePayOpsBase(configured = process.env.NEXT_PUBLIC_PAY_OPS_API_BASE): string {
  const value = configured || DEFAULT_PAY_OPS_BASE;
  if (!/^\/(?!\/)[A-Za-z0-9/_.~-]*$/.test(value)) throw new Error(`NEXT_PUBLIC_PAY_OPS_API_BASE 必须是同源绝对路径，例如 ${DEFAULT_PAY_OPS_BASE}`);
  return value.replace(/\/+$/, "");
}

/** v1 基础路径：把 v2 前缀的末段换成 v1（near-pay 退款只有 /pay-ops/v1）。不以 /v2 结尾时原样返回，由调用方自己配。 */
export function resolvePayOpsV1Base(configured = process.env.NEXT_PUBLIC_PAY_OPS_API_BASE): string {
  return resolvePayOpsBase(configured).replace(/\/v2$/, "/v1");
}

export interface PayOpsClientOptions {
  baseUrl?: string;
  fetcher?: typeof fetch;
  getToken?: () => string | null | undefined;
  locale?: () => string;
  timeoutMs?: number;
}

export interface CommandBody {
  expectedVersion?: number | string;
  reason?: string;
  [k: string]: unknown;
}

export interface PayOpsClient {
  get<T>(path: string, query?: Record<string, unknown>): Promise<T>;
  page<T>(path: string, query?: Record<string, unknown>): Promise<PayPage<T>>;
  detail<T>(path: string): Promise<PayDetail<T>>;
  command<T = unknown>(path: string, body?: CommandBody, opts?: { method?: "POST" | "PUT"; idempotent?: boolean }): Promise<T>;
}

/** 幂等号：同一路径 + 同一请求体 → 同一个号（键序无关）。 */
export function requestNoFor(path: string, body: Record<string, unknown> = {}): string {
  const s = `${path}#${stableJson(body)}`;
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return `ops-${(h >>> 0).toString(36)}`;
}

function stableJson(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(stableJson).join(",")}]`;
  if (v && typeof v === "object") return `{${Object.keys(v as object).sort().map((k) => `${JSON.stringify(k)}:${stableJson((v as Record<string, unknown>)[k])}`).join(",")}}`;
  return JSON.stringify(v ?? null);
}

function isEnvelope(v: unknown): v is { code: unknown; data?: unknown; message?: string; msg?: string } {
  return !!v && typeof v === "object" && "code" in (v as object);
}

export function createPayOpsClient(opts: PayOpsClientOptions = {}): PayOpsClient {
  const base = resolvePayOpsBase(opts.baseUrl);
  const fetcher = opts.fetcher ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  const timeoutMs = opts.timeoutMs ?? 15000;

  async function request<T>(path: string, init: { method?: string; query?: Record<string, unknown>; body?: unknown } = {}): Promise<T> {
    if (!path.startsWith("/")) throw new Error(`路径必须以 / 开头：${path}`);
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(init.query ?? {})) if (v !== undefined && v !== null && v !== "") params.set(k, String(v));
    const token = opts.getToken?.();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetcher(`${base}${path}${params.size ? `?${params}` : ""}`, {
        method: init.method ?? "GET",
        credentials: "same-origin",
        cache: "no-store",
        signal: controller.signal,
        headers: {
          Accept: "application/json",
          ...(init.body !== undefined ? { "Content-Type": "application/json" } : {}),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(opts.locale ? { "Accept-Language": opts.locale() } : {}),
        },
        ...(init.body !== undefined ? { body: JSON.stringify(init.body) } : {}),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => null) as { message?: string; reasonCode?: string; requestId?: string } | null;
        throw new PayOpsError(err?.message || `支付服务请求失败（HTTP ${res.status}）`, { status: res.status, reasonCode: err?.reasonCode, requestId: err?.requestId });
      }
      if (res.status === 204) return undefined as T;
      const value: unknown = await res.json();
      if (isEnvelope(value)) {
        const ok = value.code === 0 || value.code === "0" || value.code === 200 || value.code === "200";
        if (!ok) throw new PayOpsError(String(value.message || value.msg || "支付服务业务错误"), { status: res.status, reasonCode: String(value.code) });
        return value.data as T;
      }
      return value as T;
    } catch (e) {
      if (controller.signal.aborted) throw new PayOpsError("支付服务请求超时，请重试", { status: 0 });
      throw e;
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    get: (path, query) => request(path, { query }),
    async page<T>(path: string, query?: Record<string, unknown>) {
      const v = await request<PayPage<T> | null>(path, { query });
      return { records: v?.records ?? [], total: Number(v?.total ?? 0) };
    },
    async detail<T>(path: string) {
      const v = await request<PayDetail<T> | T>(path);
      if (v && typeof v === "object" && "record" in (v as object)) return v as PayDetail<T>;
      return { record: v as T };
    },
    command: (path, body = {}, opts = {}) =>
      request(path, { method: opts.method ?? "POST", body: opts.idempotent === false ? body : { requestNo: requestNoFor(path, body), ...body } }),
  };
}
