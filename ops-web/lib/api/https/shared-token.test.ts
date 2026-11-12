import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../error";
import { verifySharedOpsToken } from "./dashboard";
afterEach(() => vi.unstubAllGlobals());
describe("shared STAFF backend verification", () => {
  it("sends only the bearer token to /me and maps the real roles and data scope", async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ code: 0, data: { username: "finance", roles: ["FINANCE"], perms: ["settlement:view"], communityNo: "C2" } }) });
    vi.stubGlobal("fetch", fetch);
    expect(await verifySharedOpsToken("shared")).toMatchObject({ token: "shared", username: "finance", role: "FINANCE", perms: ["settlement:view"], communityNo: "C2" });
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/ops/auth/me"), expect.objectContaining({ headers: { Authorization: "Bearer shared", Accept: "application/json" }, cache: "no-store" }));
  });
  it("rejects backend failures and incomplete profiles", async () => {
    for (const response of [{ ok: false, data: { username: "forged", roles: ["SUPER_ADMIN"] } }, { ok: true, data: {} }]) {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: response.ok, json: async () => ({ code: response.ok ? 0 : 401, data: response.data }) }));
      await expect(verifySharedOpsToken("t")).rejects.toThrow();
    }
  });
  it("distinguishes confirmed authentication rejection from retryable server outages", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ status: 401, ok: false }));
    await expect(verifySharedOpsToken("t")).rejects.toMatchObject({ code: 401 });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ status: 503, ok: false, json: async () => ({ code: 503 }) }));
    try { await verifySharedOpsToken("t"); throw new Error("unexpected success"); }
    catch (error) { expect(error).not.toBeInstanceOf(ApiError); expect((error as Error).message).toContain("retry"); }
  });

});
