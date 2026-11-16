import { describe, expect, it, vi } from "vitest";
import { fetchPaymentAccess } from "./system-switcher";

const reply = (status: number, body: unknown) => vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;

describe("「支付运营」入口按支付端 /me/access 显隐", () => {
  it("有有效权限才显示；带信封与不带信封都认", async () => {
    expect(await fetchPaymentAccess("t", reply(200, { code: 0, data: { permissions: ["order:view"] } }))).toBe(true);
    expect(await fetchPaymentAccess("t", reply(200, { permissions: ["order:view"] }))).toBe(true);
  });
  it("无权限、未授权、业务码非 0 都不显示", async () => {
    expect(await fetchPaymentAccess("t", reply(200, { code: 0, data: { permissions: [] } }))).toBe(false);
    expect(await fetchPaymentAccess("t", reply(401, {}))).toBe(false);
    expect(await fetchPaymentAccess("t", reply(403, {}))).toBe(false);
    expect(await fetchPaymentAccess("t", reply(200, { code: 403, message: "denied" }))).toBe(false);
  });
  it("带的是当前令牌", async () => {
    const f = reply(200, { permissions: ["x"] });
    await fetchPaymentAccess("tok", f);
    expect((f as unknown as ReturnType<typeof vi.fn>).mock.calls[0][1].headers.Authorization).toBe("Bearer tok");
  });
});
