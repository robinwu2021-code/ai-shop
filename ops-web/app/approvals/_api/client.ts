// OpsKit 审批接口客户端（TDD-OpsKit审批对接 §1.5，契约 §1–§3.2）。
//
// - 只走同源：ai-shop 网关透传 /ops/opskit/v1/** → OpsKit /api/opskit/v1/**（OpsKit 只在内网，浏览器不直连）。
//   基址 NEXT_PUBLIC_OPSKIT_API_BASE，空 = `${NEXT_PUBLIC_API_BASE}/ops/opskit/v1`（与其它运营接口同源同前缀）。
// - 令牌：当前运营会话令牌原样带上；OpsKit 回 ai-shop 内省换身份，按钮与可见范围以 OpsKit 返回为准，页面不自己判。
// - 错误：{error, message, args, traceId, errors[{path, error}]} → OpsKitError；文案按 error 键查 error-keys.json。
// - 放在 app/approvals 而不是 lib/api/https：后者受 shop openapi 对账闸门约束，而这些端点属于 OpsKit。
import type {
  ApprovalQuery, ApprovalRow, ApprovalView, HistoryEntry, InstanceQuery, InstanceRow, Me, Page, SaveResult, SubjectHistory,
  Template, TemplateSummary, TemplateVersion, TodoCount, TodoRow,
} from "./types";

export const WORKSPACE = "ext:PLATFORM";

export class OpsKitError extends Error {
  status: number;
  key?: string;
  args?: Record<string, unknown>;
  traceId?: string;
  fieldErrors: { path: string; error: string }[];
  constructor(message: string, init: { status: number; key?: string; args?: Record<string, unknown>; traceId?: string; fieldErrors?: { path: string; error: string }[] }) {
    super(message);
    this.name = "OpsKitError";
    this.status = init.status;
    this.key = init.key;
    this.args = init.args;
    this.traceId = init.traceId;
    this.fieldErrors = init.fieldErrors ?? [];
  }
}

export function resolveOpsKitBase(configured = process.env.NEXT_PUBLIC_OPSKIT_API_BASE, apiBase = process.env.NEXT_PUBLIC_API_BASE ?? ""): string {
  const value = configured || `${apiBase.replace(/\/+$/, "")}/ops/opskit/v1`;
  if (!/^\/(?!\/)[A-Za-z0-9/_.~:-]*$/.test(value)) throw new Error("NEXT_PUBLIC_OPSKIT_API_BASE 必须是同源绝对路径，例如 /ops/opskit/v1");
  return value.replace(/\/+$/, "");
}

export interface OpsKitClientOptions {
  baseUrl?: string;
  fetcher?: typeof fetch;
  getToken?: () => string | null | undefined;
  locale?: () => string;
  timeoutMs?: number;
}

export interface OpsKitApi {
  todosCount(): Promise<TodoCount>;
  todos(q: { page: number; size: number }): Promise<Page<TodoRow>>;
  approvals(q: ApprovalQuery): Promise<Page<ApprovalRow>>;
  approvalCount(q: Omit<ApprovalQuery, "page" | "size" | "status">): Promise<Record<string, number>>;
  approval(no: string): Promise<ApprovalView>;
  decide(no: string, body: { taskNo: string; decision: "APPROVE" | "REJECT"; reason?: string }): Promise<ApprovalView>;
  withdraw(no: string): Promise<ApprovalView>;
  instances(q: InstanceQuery): Promise<Page<InstanceRow>>;
  transition(instanceNo: string, code: string, comment?: string): Promise<unknown>;
  history(instanceNo: string): Promise<HistoryEntry[]>;
  subjectHistory(type: string, ref: string): Promise<SubjectHistory>;
  /** 实例 → 最新一张审批单号（「我提交的」「生效失败」按实例列，点进去要看审批详情） */
  approvalOf(row: { instanceNo: string; subject?: { type: string; ref: string } }): Promise<string | null>;
  templates(): Promise<TemplateSummary[]>;
  template(code: string): Promise<Template>;
  saveTemplate(code: string, t: Template): Promise<SaveResult>;
  versions(code: string): Promise<TemplateVersion[]>;
  setEnabled(code: string, enabled: boolean): Promise<unknown>;
  me(): Promise<Me>;
}

export function createOpsKitApi(opts: OpsKitClientOptions = {}): OpsKitApi {
  const base = `${resolveOpsKitBase(opts.baseUrl)}/workspaces/${WORKSPACE}`;
  const fetcher = opts.fetcher ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  const timeoutMs = opts.timeoutMs ?? 15000;

  async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
    const token = opts.getToken?.();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetcher(`${base}${path}`, {
        method,
        credentials: "same-origin",
        cache: "no-store",
        signal: controller.signal,
        headers: {
          Accept: "application/json",
          ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(opts.locale ? { "Accept-Language": opts.locale() } : {}),
        },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => null) as { error?: string; message?: string; args?: Record<string, unknown>; traceId?: string; errors?: { path: string; error: string }[] } | null;
        throw new OpsKitError(err?.message || `HTTP ${res.status}`, {
          status: res.status, key: err?.error ?? (res.status === 401 ? "opskit.auth.unauthenticated" : undefined),
          args: err?.args, traceId: err?.traceId ?? res.headers.get("traceId") ?? undefined, fieldErrors: err?.errors,
        });
      }
      if (res.status === 204) return undefined as T;
      const text = await res.text();
      return (text ? JSON.parse(text) : undefined) as T;
    } catch (e) {
      if (controller.signal.aborted) throw new OpsKitError("timeout", { status: 0, key: "opskit.client.timeout" });
      throw e;
    } finally {
      clearTimeout(timer);
    }
  }
  const enc = encodeURIComponent;
  return {
    todosCount: () => call("POST", "/todos/count", {}),
    todos: (q) => call("POST", "/todos/query", { origin: "PROCESS", ...q }),
    approvals: (q) => call("POST", "/approvals/query", q),
    approvalCount: (q) => call("POST", "/approvals/count", q),
    approval: (no) => call("GET", `/approvals/${enc(no)}`),
    decide: (no, body) => call("POST", `/approvals/${enc(no)}/decide`, body),
    withdraw: (no) => call("POST", `/approvals/${enc(no)}/withdraw`, {}),
    instances: (q) => call("POST", "/instances/query", q),
    transition: (no, code, comment) => call("POST", `/instances/${enc(no)}/transitions/${enc(code)}`, comment ? { comment } : {}),
    history: (no) => call("GET", `/instances/${enc(no)}/history`),
    subjectHistory: (type, ref) => call("GET", `/subjects/${enc(type)}/${enc(ref)}/history`),
    async approvalOf(row) {
      if (!row.subject) return null;
      const h = await call<SubjectHistory>("GET", `/subjects/${enc(row.subject.type)}/${enc(row.subject.ref)}/history`);
      const hit = h.instances?.find((i) => i.instance?.instanceNo === row.instanceNo);
      const approvals = [...(hit?.approvals ?? [])].sort((a, b) => (b.requestedAtMs ?? 0) - (a.requestedAtMs ?? 0));
      return approvals[0]?.approvalNo ?? null;
    },
    templates: () => call("GET", "/approval-templates"),
    template: (code) => call("GET", `/approval-templates/${enc(code)}`),
    saveTemplate: (code, t) => call("PUT", `/approval-templates/${enc(code)}`, t),
    versions: (code) => call("GET", `/approval-templates/${enc(code)}/versions`),
    setEnabled: (code, enabled) => call("POST", `/approval-templates/${enc(code)}/${enabled ? "enable" : "disable"}`, {}),
    me: () => call("GET", "/me"),
  };
}
