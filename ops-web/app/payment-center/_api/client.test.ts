import { describe, expect, it, vi } from "vitest";
import { DEFAULT_PAY_OPS_BASE, PayOpsError, createPayOpsClient, requestNoFor, resolvePayOpsBase } from "./client";

function fakeFetch(status: number, body: unknown) {
  return vi.fn(async (_url: string, _init?: RequestInit) => new Response(body === undefined ? null : JSON.stringify(body), { status }));
}

describe("resolvePayOpsBase", () => {
  it("默认同源路径；只接受同源绝对路径", () => {
    expect(resolvePayOpsBase(undefined)).toBe(DEFAULT_PAY_OPS_BASE);
    expect(resolvePayOpsBase("/gw/pay-ops/v2/")).toBe("/gw/pay-ops/v2");
    expect(() => resolvePayOpsBase("https://evil.example/pay")).toThrow();
    expect(() => resolvePayOpsBase("//evil.example/pay")).toThrow();
  });
});

describe("createPayOpsClient", () => {
  it("拼查询串、带令牌，空值不发", async () => {
    const f = fakeFetch(200, { records: [{ id: 1 }], total: 1 });
    const c = createPayOpsClient({ baseUrl: "/x", fetcher: f as unknown as typeof fetch, getToken: () => "t0k" });
    const page = await c.page("/orders", { status: "PAID", merchantNo: "", page: 2 });
    expect(page).toEqual({ records: [{ id: 1 }], total: 1 });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("/x/orders?status=PAID&page=2");
    expect((init!.headers as Record<string, string>).Authorization).toBe("Bearer t0k");
    expect(init!.credentials).toBe("same-origin");
  });

  it("解 {code,data} 信封；业务错误抛 PayOpsError", async () => {
    const ok = createPayOpsClient({ baseUrl: "/x", fetcher: fakeFetch(200, { code: 0, data: { a: 1 } }) as unknown as typeof fetch });
    expect(await ok.get("/a")).toEqual({ a: 1 });
    const bad = createPayOpsClient({ baseUrl: "/x", fetcher: fakeFetch(200, { code: 40001, message: "状态不允许" }) as unknown as typeof fetch });
    await expect(bad.get("/a")).rejects.toMatchObject({ name: "PayOpsError", message: "状态不允许" });
  });

  it("HTTP 错误带 reasonCode 与 requestId", async () => {
    const c = createPayOpsClient({ baseUrl: "/x", fetcher: fakeFetch(409, { code: 409, reasonCode: "VERSION_CONFLICT", message: "已被修改", requestId: "r1" }) as unknown as typeof fetch });
    const e = await c.get("/a").catch((x) => x);
    expect(e).toBeInstanceOf(PayOpsError);
    expect(e).toMatchObject({ status: 409, reasonCode: "VERSION_CONFLICT", requestId: "r1", message: "已被修改" });
  });

  it("详情统一成 {record, version}", async () => {
    const wrapped = createPayOpsClient({ baseUrl: "/x", fetcher: fakeFetch(200, { record: { no: "P1" }, version: 3 }) as unknown as typeof fetch });
    expect(await wrapped.detail("/orders/P1")).toEqual({ record: { no: "P1" }, version: 3 });
    const bare = createPayOpsClient({ baseUrl: "/x", fetcher: fakeFetch(200, { no: "P1" }) as unknown as typeof fetch });
    expect(await bare.detail("/orders/P1")).toEqual({ record: { no: "P1" } });
  });

  it("命令的幂等号：原样重发同号，改了内容或版本换号；可关闭", async () => {
    const f = fakeFetch(200, { code: 0, data: null });
    const c = createPayOpsClient({ baseUrl: "/x", fetcher: f as unknown as typeof fetch });
    await c.command("/channels/C1/disable", { expectedVersion: 3, reason: "维护" });
    await c.command("/channels/C1/disable", { reason: "维护", expectedVersion: 3 });
    await c.command("/channels/C1/disable", { expectedVersion: 3, reason: "改了理由" });
    await c.command("/orders/P1/dismiss", { reason: "重复下单" }, { idempotent: false });
    await c.command("/channels/C1", { expectedVersion: 3, reason: "x" }, { method: "PUT" });
    const bodies = f.mock.calls.map((call) => JSON.parse(String(call[1]!.body)));
    expect(bodies[0]).toEqual({ requestNo: requestNoFor("/channels/C1/disable", { expectedVersion: 3, reason: "维护" }), expectedVersion: 3, reason: "维护" });
    expect(bodies[1].requestNo).toBe(bodies[0].requestNo);
    expect(bodies[2].requestNo).not.toBe(bodies[0].requestNo);
    expect(bodies[3]).toEqual({ reason: "重复下单" });
    expect(f.mock.calls[4][1]!.method).toBe("PUT");
    expect(requestNoFor("/a", { v: 1 })).toMatch(/^ops-[0-9a-z]+$/);
  });

  it("上传：multipart 带表单字段与 requestNo（同文件同字段同号），不设 JSON 头；解信封", async () => {
    const f = fakeFetch(200, { code: 0, data: { draft: { draftVersion: 2 } } });
    const c = createPayOpsClient({ baseUrl: "/x", fetcher: f as unknown as typeof fetch, getToken: () => "t" });
    const file = new File([new Uint8Array([0x89, 0x50])], "a.png", { type: "image/png", lastModified: 1 });
    expect(await c.upload("/onboardings/O1/files", { slot: "LICENSE_1", expectedVersion: 3, expectedDraftVersion: 1 }, file)).toEqual({ draft: { draftVersion: 2 } });
    await c.upload("/onboardings/O1/files", { slot: "LICENSE_1", expectedVersion: 3, expectedDraftVersion: 1 }, file);
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("/x/onboardings/O1/files");
    const form = init!.body as FormData;
    expect([form.get("slot"), form.get("expectedVersion"), form.get("expectedDraftVersion")]).toEqual(["LICENSE_1", "3", "1"]);
    expect((form.get("file") as File).name).toBe("a.png");
    expect(form.get("requestNo")).toBe((f.mock.calls[1][1]!.body as FormData).get("requestNo"));
    expect((init!.headers as Record<string, string>)["Content-Type"]).toBeUndefined();
  });

  it("取文件：返回 Blob，Accept 只要图片与 PDF；失败照常抛 PayOpsError", async () => {
    const f = vi.fn(async () => new Response(new Uint8Array([1, 2, 3]), { status: 200, headers: { "Content-Type": "application/pdf" } }));
    const c = createPayOpsClient({ baseUrl: "/x", fetcher: f as unknown as typeof fetch });
    const b = await c.blob("/onboardings/O1/files/abc");
    expect(b.size).toBe(3);
    expect(((f.mock.calls[0] as unknown as [string, RequestInit])[1].headers as Record<string, string>).Accept).toBe("image/png,image/jpeg,application/pdf");
    const bad = createPayOpsClient({ baseUrl: "/x", fetcher: fakeFetch(409, { reasonCode: "STATE_CONFLICT", message: "附件已过期" }) as unknown as typeof fetch });
    await expect(bad.blob("/f")).rejects.toMatchObject({ message: "附件已过期", reasonCode: "STATE_CONFLICT" });
  });
});
