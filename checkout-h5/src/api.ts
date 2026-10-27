/** /checkout/v1 的客户端。响应体是 {code, message, data, requestId}，状态码是真的（安全规范 §5.2） */

export interface SessionView {
  payOrderNo: string;
  payeeName: string;
  storeName: string;
  amountMinor: number;
  currency: string;
  currencyScale: number | null;
  market: string | null;
  status: "OPEN" | "PAYING" | "DONE";
  payMethods: string[];
  expireAt: number;
}

export interface CodeView {
  payeeName: string;
  storeName: string;
  kind: string;
  tableRef: string | null;
  currency: string;
  currencyScale: number | null;
  market: string | null;
  singleLimitMinor: number;
}

export interface Issued {
  payOrderNo: string;
  token: string;
  expireAt: number;
}

export interface StartResult {
  payOrderNo: string;
  outTradeNo: string;
  payChannel: string;
  payParams: Record<string, string>;
}

export type Outcome = "SUCCEEDED" | "PROCESSING" | "FAILED";

/** 失效（404 / 410）与其它错误分开：前者整页换成「链接已失效」，后者原地提示 */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: number | null, message: string) {
    super(message);
  }

  get gone(): boolean {
    return this.status === 404 || this.status === 410;
  }
}

const BASE = "/checkout/v1";

async function call<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(BASE + path, {
      method,
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "same-origin",
      cache: "no-store",
      referrerPolicy: "no-referrer",
    });
  } catch {
    throw new ApiError(0, null, "network");
  }
  let json: { code?: number; message?: string; data?: T } = {};
  try {
    json = await res.json();
  } catch {
    // 非 JSON（nginx 错误页之类）按状态码处理
  }
  if (!res.ok) throw new ApiError(res.status, json.code ?? null, json.message ?? "");
  return json.data as T;
}

const seg = (s: string) => encodeURIComponent(s);

export const api = {
  session: (token: string) => call<SessionView>("GET", `/sessions/${seg(token)}`),
  pay: (token: string, payMethod: string, payerOpenId?: string, payerAppId?: string) =>
    call<StartResult>("POST", `/sessions/${seg(token)}/pay`, { payMethod, payerOpenId, payerAppId }),
  result: (token: string) => call<{ result: Outcome }>("GET", `/sessions/${seg(token)}/result`),
  code: (qrCode: string) => call<CodeView>("GET", `/codes/${seg(qrCode)}`),
  orderByCode: (qrCode: string, amountMinor: number) =>
    call<Issued>("POST", `/codes/${seg(qrCode)}/orders`, { amountMinor }),
};
