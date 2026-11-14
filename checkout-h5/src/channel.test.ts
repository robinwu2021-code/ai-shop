import { describe, expect, it, vi } from "vitest";
import { launch } from "./channel";

function fakeWin(extra: Record<string, unknown> = {}) {
  return { location: { assign: vi.fn() }, ...extra } as unknown as Window;
}

describe("唤起渠道", () => {
  it("★★ 测试通道：不唤起，直接去结果页", async () => {
    expect(await launch("TEST", { prepayId: "test_1" }, fakeWin())).toBe("test");
  });

  it("★★★ 跳转地址只认 https：javascript: 之类一律不碰", async () => {
    const win = fakeWin();
    expect(await launch("WECHAT", { h5Url: "javascript:alert(1)" }, win)).toBe("unsupported");
    expect((win.location.assign as ReturnType<typeof vi.fn>)).not.toHaveBeenCalled();
    expect(await launch("WECHAT", { mwebUrl: "https://wx.example/pay" }, win)).toBe("redirected");
    expect(win.location.assign).toHaveBeenCalledWith("https://wx.example/pay");
  });

  it("★★ 微信内 JSAPI：五件套交给 WeixinJSBridge；不在微信里不硬唤", async () => {
    const params = { appId: "a", timeStamp: "1", nonceStr: "n", package: "prepay_id=x", signType: "RSA", paySign: "s" };
    expect(await launch("WECHAT", params, fakeWin())).toBe("unsupported");
    const invoke = vi.fn((_n: string, _p: unknown, cb: (r: { err_msg: string }) => void) => cb({ err_msg: "get_brand_wcpay_request:ok" }));
    expect(await launch("WECHAT", params, fakeWin({ WeixinJSBridge: { invoke } }))).toBe("invoked");
    expect(invoke.mock.calls[0][0]).toBe("getBrandWCPayRequest");
  });
});
