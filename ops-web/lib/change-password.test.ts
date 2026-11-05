// 登录后修改密码：http 路径、mock 落库与校验口径。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("@/lib/api/mocks/_wait", () => ({ wait: async <T,>(v: T) => v }));
vi.mock("@/lib/auth", async (orig) => ({
  ...(await orig<typeof import("@/lib/auth")>()),
  currentAuth: () => ({ username: "admin", role: "SUPER_ADMIN", token: "mock-SUPER_ADMIN" }),
}));
import { ApiError } from "@/lib/api/error";
import { dashboardMock, resetMockPasswords } from "@/lib/api/mocks/dashboard";
import { changePasswordProblem } from "@/components/layout/change-password";

const code = async (f: () => Promise<unknown>) => ((await Promise.resolve().then(f).then(() => null, (e) => e)) as ApiError | null)?.code;

describe("修改密码 · mock 与后端同构", () => {
  beforeEach(() => resetMockPasswords());

  it("改完之后旧密码登不进去、新密码登得进去", async () => {
    await dashboardMock.changeOwnPassword("anything", "NewPass-2026");
    expect(await code(() => dashboardMock.login("admin", "anything"))).toBe(400);
    expect((await dashboardMock.login("admin", "NewPass-2026")).username).toBe("admin");
  });

  it("旧密码不对 → 10401（业务码，不是 HTTP 401）；新密码 < 8 位 → 10400", async () => {
    await dashboardMock.changeOwnPassword("anything", "NewPass-2026");
    expect(await code(() => dashboardMock.changeOwnPassword("wrong-old", "Another-2026"))).toBe(10401);
    expect(await code(() => dashboardMock.changeOwnPassword("NewPass-2026", "short"))).toBe(10400);
  });
});

describe("修改密码 · 前端预校验", () => {
  it("太短 / 与旧密码相同 / 两次不一致", () => {
    expect(changePasswordProblem("old-pass", "short", "short")).toBe("account.tooShort");
    expect(changePasswordProblem("same-pass-1", "same-pass-1", "same-pass-1")).toBe("account.sameAsOld");
    expect(changePasswordProblem("old-pass", "new-pass-1", "new-pass-2")).toBe("account.mismatch");
    expect(changePasswordProblem("old-pass", "new-pass-1", "new-pass-1")).toBeNull();
  });
});

describe("修改密码 · http", () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.resetModules(); });

  it("调 POST /ops/staffs/me/password，body 只有 oldPassword / newPassword", async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ code: 0, data: null }) });
    vi.stubGlobal("fetch", fetch);
    const { dashboardHttp } = await import("@/lib/api/https/dashboard");
    await dashboardHttp.changeOwnPassword("old-pass", "new-pass-1");
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toMatch(/\/ops\/staffs\/me\/password$/);
    expect(JSON.parse(init.body)).toEqual({ oldPassword: "old-pass", newPassword: "new-pass-1" });
  });
});
