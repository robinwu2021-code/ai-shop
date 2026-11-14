// 员工免二次验证白名单：mock 与后端同口径（理由必填、不能给自己勾、写高危审计），http 路径与字段映射。
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("@/lib/api/mocks/_wait", () => ({ wait: async <T,>(v: T) => v }));
vi.mock("@/lib/auth", async (orig) => ({
  ...(await orig<typeof import("@/lib/auth")>()),
  currentAuth: () => ({ username: "admin", role: "SUPER_ADMIN", token: "mock-SUPER_ADMIN" }),
}));
import * as db from "@/lib/mock/db";
import { iamMock } from "@/lib/api/mocks/iam";

describe("免二次验证白名单 · mock", () => {
  it("理由必填；不能给自己勾；成功后回显并写高危审计", async () => {
    const other = db.staffs.find((s) => s.username !== "admin")!;
    const me = db.staffs.find((s) => s.username === "admin")!;
    await expect(iamMock.setStaffSecondFactorExempt(other.staffNo, true, "  ")).rejects.toThrow();
    await expect(iamMock.setStaffSecondFactorExempt(me.staffNo, true, "自己")).rejects.toThrow();
    const r = await iamMock.setStaffSecondFactorExempt(other.staffNo, true, "自动化测试账号");
    expect(r.secondFactorExempt).toBe(true);
    expect(db.auditLogs[0]).toMatchObject({ critical: true, detail: "自动化测试账号" });
  });
});

describe("免二次验证白名单 · http", () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.resetModules(); });
  it("POST /ops/staffs/{no}/second-factor-exempt，回包 secondFactorExempt 映射到 Staff", async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ code: 0,
      data: { staffNo: "ST-1", username: "u@x", status: "ACTIVE", roles: [], secondFactorExempt: true } }) });
    vi.stubGlobal("fetch", fetch);
    const { iamHttp } = await import("@/lib/api/https/iam");
    const s = await iamHttp.setStaffSecondFactorExempt("ST-1", true, "理由");
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toMatch(/\/ops\/staffs\/ST-1\/second-factor-exempt$/);
    expect(JSON.parse(init.body)).toEqual({ exempt: true, reason: "理由" });
    expect(s.secondFactorExempt).toBe(true);
  });
});
