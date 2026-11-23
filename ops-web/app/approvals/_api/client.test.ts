import { describe, expect, it } from "vitest";
import { createOpsKitApi, OpsKitError, resolveOpsKitBase } from "./client";
import keys from "./error-keys.json";

type Call = { url: string; init: RequestInit };

function fakeFetch(respond: (url: string, init: RequestInit) => { status?: number; body?: unknown; headers?: Record<string, string> }) {
  const calls: Call[] = [];
  const fetcher = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const r = respond(url, init);
    return new Response(r.body === undefined ? "" : JSON.stringify(r.body), { status: r.status ?? 200, headers: r.headers });
  }) as unknown as typeof fetch;
  return { calls, fetcher };
}

describe("OpsKit 审批客户端（TDD-OpsKit审批对接 §1.5）", () => {
  it("基址：默认跟随商城接口同源前缀 /ops/opskit/v1；只收同源绝对路径", () => {
    expect(resolveOpsKitBase(undefined, "")).toBe("/ops/opskit/v1");
    expect(resolveOpsKitBase(undefined, "/ops-shop")).toBe("/ops-shop/ops/opskit/v1");
    expect(resolveOpsKitBase("/ops-shop-v2/ops/opskit/v1/", "")).toBe("/ops-shop-v2/ops/opskit/v1");
    expect(() => resolveOpsKitBase("https://evil.example/x", "")).toThrow();
    expect(() => resolveOpsKitBase("//evil.example/x", "")).toThrow();
  });

  it("按平台空间寻址，带令牌与界面语言；待办查询 origin 用大写 PROCESS", async () => {
    const { calls, fetcher } = fakeFetch(() => ({ body: { list: [], page: 1, size: 20, total: 0 } }));
    const api = createOpsKitApi({ baseUrl: "/ops/opskit/v1", fetcher, getToken: () => "otk_abc", locale: () => "en" });
    await api.todos({ page: 1, size: 20 });
    expect(calls[0].url).toBe("/ops/opskit/v1/workspaces/ext:PLATFORM/todos/query");
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ origin: "PROCESS", page: 1, size: 20 });
    const h = calls[0].init.headers as Record<string, string>;
    expect(h.Authorization).toBe("Bearer otk_abc");
    expect(h["Accept-Language"]).toBe("en");
  });

  it("错误体 → OpsKitError：错误键、字段错误、traceId（响应头兜底）", async () => {
    const { fetcher } = fakeFetch(() => ({
      status: 422, headers: { traceId: "tr-9" },
      body: { error: "opskit.definition.invalid", message: "bad", errors: [{ path: "steps[1].approver.value", error: "opskit.validation.required" }] },
    }));
    const api = createOpsKitApi({ baseUrl: "/ops/opskit/v1", fetcher });
    const e = await api.saveTemplate("shop_store_opening", { code: "shop_store_opening", name: { zh: "x" }, steps: [] }).catch((x) => x);
    expect(e).toBeInstanceOf(OpsKitError);
    expect(e.status).toBe(422);
    expect(e.key).toBe("opskit.definition.invalid");
    expect(e.traceId).toBe("tr-9");
    expect(e.fieldErrors).toEqual([{ path: "steps[1].approver.value", error: "opskit.validation.required" }]);
  });

  it("实例 → 最新一张审批单（「我提交的」「生效失败」点进详情）", async () => {
    const { calls, fetcher } = fakeFetch(() => ({ body: { type: "shop.store_opening", ref: "S1", instances: [
      { instance: { instanceNo: "PI0" }, approvals: [{ approvalNo: "AP0", requestedAtMs: 1 }] },
      { instance: { instanceNo: "PI1" }, approvals: [{ approvalNo: "AP1", requestedAtMs: 1 }, { approvalNo: "AP2", requestedAtMs: 5 }] },
    ] } }));
    const api = createOpsKitApi({ baseUrl: "/ops/opskit/v1", fetcher });
    expect(await api.approvalOf({ instanceNo: "PI1", subject: { type: "shop.store_opening", ref: "S1" } })).toBe("AP2");
    expect(calls[0].url).toBe("/ops/opskit/v1/workspaces/ext:PLATFORM/subjects/shop.store_opening/S1/history");
    expect(await api.approvalOf({ instanceNo: "PI9" })).toBeNull();
  });

  it("错误码表：whyNot 与常见错误都有中英文案", () => {
    const table = keys as Record<string, { zh?: string; en?: string }>;
    for (const k of ["opskit.approval.self_review", "opskit.approval.reason_required", "opskit.state_changed", "opskit.approval.withdraw_forbidden"]) {
      expect(table[k]?.zh, k).toBeTruthy();
      expect(table[k]?.en, k).toBeTruthy();
    }
  });
});
