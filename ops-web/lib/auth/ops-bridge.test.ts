import { describe, expect, it, vi } from "vitest";
import { clearOpsSessions, currentSharedToken, loginReturnHref, migrateLegacySession, paymentOpsHref, readSharedToken, returnAfterLogin, safeReturnTo, shareOpsToken } from "./ops-bridge";
const origin = "https://ops.example.test";
describe("same-origin operations bridge", () => {
  it("preserves payment and shop deep links including query/hash", () => {
    for (const path of ["/oms/payments/?status=FAILED#detail", "/ops-shop/orders/?tab=refund#r1"]) {
      expect(safeReturnTo(path, origin)).toBe(path);
      expect(safeReturnTo(`${origin}${path}`, origin)).toBe(path);
    }
  });
  it("rejects open redirects, path prefix confusion and login loops", () => {
    for (const path of ["https://evil.test/oms/", "//evil.test/oms/", "javascript:alert(1)", "/oms-evil/", "/ops-shop/login/", "/oms/../../secret", "/oms/%2f..%2fsecret", "/oms/\\evil", "/other/"]) {
      expect(safeReturnTo(path, origin), path).toBeNull();
    }
  });
  it("accepts only same-origin configured payment roots", () => {
    expect(safeReturnTo("/pay-ops/p?x=1#y", origin, "/ops-shop", "/pay-ops/")).toBe("/pay-ops/p?x=1#y");
    expect(paymentOpsHref(origin, "zh-TW", "https://evil.test/steal")).toBe("/oms/?locale=zh-TW");
    expect(paymentOpsHref(origin, "zh", "/pay-ops/")).toBe("/pay-ops/?locale=zh-CN");
  });
  it("encodes original deep links into the login returnTo", () => {
    const href = loginReturnHref({ pathname: "/ops-shop/orders/", search: "?a=1&b=2", hash: "#refund" });
    expect(new URL(href, origin).searchParams.get("returnTo")).toBe("/ops-shop/orders/?a=1&b=2#refund");
  });
  it("reads only a token and ignores forged shared profiles", () => {
    const storage = { getItem: () => JSON.stringify({ token: "t", role: "SUPER_ADMIN", perms: ["*"] }) };
    expect(readSharedToken(storage)).toBe("t");
    for (const value of ["garbage", "null", '{"token":4}', '{"token":""}']) expect(readSharedToken({ getItem: () => value })).toBeNull();
  });
  it("replaces with an authorized return link and defaults to the shop home", () => {
    const replace = vi.fn(); const home = vi.fn();
    returnAfterLogin("/oms/payments/?status=FAILED#detail", { origin, replace }, home);
    expect(replace).toHaveBeenCalledWith("/oms/payments/?status=FAILED#detail");
    expect(home).not.toHaveBeenCalled();
    for (const returnTo of [null, "https://evil.test/oms/"]) returnAfterLogin(returnTo, { origin, replace }, home);
    expect(home).toHaveBeenCalledTimes(2);
  });
  it("shares one cross-app key, clears it with every legacy copy, and migrates the old shop cache once", () => {
    const local = new Map<string, string>(); const session = new Map<string, string>();
    const mk = (m: Map<string, string>) => ({ getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => m.set(k, v), removeItem: (k: string) => m.delete(k) });
    vi.stubGlobal("window", {}); vi.stubGlobal("localStorage", mk(local)); vi.stubGlobal("sessionStorage", mk(session));
    try {
      shareOpsToken("real-token", "ops_zhang");
      expect(JSON.parse(local.get("neargo-ops-auth")!)).toMatchObject({ token: "real-token", username: "ops_zhang" });
      expect(currentSharedToken()).toBe("real-token");
      session.set("nearpay-ops-session", "cached-payment"); session.set("neargo-ops-session", "{}"); local.set("shop-ops-auth", "cached-shop");
      clearOpsSessions();
      expect(local.has("neargo-ops-auth")).toBe(false);
      expect(local.has("shop-ops-auth")).toBe(false);
      expect(session.size).toBe(0);
      // 上线后第一次加载：只有旧的商城缓存 → 搬到新键
      local.set("shop-ops-auth", JSON.stringify({ state: { token: "legacy", username: "old" } }));
      migrateLegacySession();
      expect(currentSharedToken()).toBe("legacy");
      // 新键已有值时不覆盖
      local.set("shop-ops-auth", JSON.stringify({ state: { token: "stale" } }));
      migrateLegacySession();
      expect(currentSharedToken()).toBe("legacy");
    } finally { vi.unstubAllGlobals(); }
  });

});

describe("language selected during shared login", () => {
  it("returns using the newly selected language while retaining payment filters and hash", () => {
    const replace = vi.fn(); const home = vi.fn();
    returnAfterLogin("/oms/payments/?status=FAILED&locale=zh-TW&merchant=M1#detail", { origin, replace }, home, "en");
    expect(replace).toHaveBeenCalledWith("/oms/payments/?status=FAILED&locale=en&merchant=M1#detail");
    expect(home).not.toHaveBeenCalled();
  });
  it("maps Simplified Chinese to the cross-app tag and retains Traditional Chinese", () => {
    const replace = vi.fn(); const home = vi.fn();
    returnAfterLogin("/oms/?locale=en#detail", { origin, replace }, home, "zh");
    expect(replace).toHaveBeenLastCalledWith("/oms/?locale=zh-CN#detail");
    returnAfterLogin("/oms/?locale=en#detail", { origin, replace }, home, "zh-TW");
    expect(replace).toHaveBeenLastCalledWith("/oms/?locale=zh-TW#detail");
  });
  it("retains an unhinted deep link and still rejects untrusted return destinations", () => {
    const replace = vi.fn(); const home = vi.fn();
    returnAfterLogin("/oms/?status=FAILED#detail", { origin, replace }, home, "en");
    expect(replace).toHaveBeenCalledWith("/oms/?status=FAILED#detail");
    returnAfterLogin("https://evil.test/oms/?locale=zh-TW", { origin, replace }, home, "en");
    expect(home).toHaveBeenCalledOnce();
  });
});
