import { describe, expect, it, vi } from "vitest";
import { clearOpsSessions, loginReturnHref, paymentOpsHref, readSharedToken, returnAfterLogin, safeReturnTo, shareOpsToken } from "./ops-bridge";
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
  it("shares only the token, then clears both sessions and blocks automatic relogin", () => {
    const values = new Map<string, string>();
    const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) };
    vi.stubGlobal("window", {}); vi.stubGlobal("sessionStorage", storage); vi.stubGlobal("localStorage", storage);
    try {
      shareOpsToken("real-token");
      expect(JSON.parse(values.get("neargo-ops-session")!)).toEqual({ token: "real-token" });
      values.set("nearpay-ops-session", "cached-payment"); values.set("shop-ops-auth", "cached-shop");
      clearOpsSessions();
      expect(values.has("neargo-ops-session")).toBe(false);
      expect(values.has("nearpay-ops-session")).toBe(false);
      expect(values.has("shop-ops-auth")).toBe(false);
      expect(values.get("nearpay-ops-no-shared")).toBe("1");
      shareOpsToken("next-login");
      expect(values.has("nearpay-ops-no-shared")).toBe(false);
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
